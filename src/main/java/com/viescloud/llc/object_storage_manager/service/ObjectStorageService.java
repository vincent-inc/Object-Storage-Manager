package com.viescloud.llc.object_storage_manager.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.util.ObjectUtils;

import com.viescloud.llc.object_storage_manager.dao.ObjectStorageDao;
import com.viescloud.llc.object_storage_manager.model.ObjectStorageData;
import com.viescloud.llc.viesspringutils.exception.HttpResponseThrowers;
import com.viescloud.llc.viesspringutils.model.UserPermissionEnum;
import com.viescloud.llc.viesspringutils.repository.DatabaseCall;
import com.viescloud.llc.viesspringutils.service.ViesServiceWithUserAccess;
import com.viescloud.llc.viesspringutils.util.ExpirableHashSet;
import com.viescloud.llc.viesspringutils.util.ReflectionUtils;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class ObjectStorageService<I, T extends ObjectStorageData, D extends ObjectStorageDao<T, I>> extends ViesServiceWithUserAccess<I, T, D> {

    ExpirableHashSet<T> ignoreFetchFileFlagCache;

    public ObjectStorageService(DatabaseCall<I, T> databaseCall, D repositoryDao) {
        super(databaseCall, repositoryDao);
        this.ignoreFetchFileFlagCache = new ExpirableHashSet<>(Duration.ofSeconds(30));
    }

    protected abstract void checkIfFileDirectoryExist(String path);
    protected abstract void moveFileOnStorage(String originalPath, String newPath);
    protected abstract byte[] readRawOnStorage(String path);
    protected abstract boolean isFileExistOnStorage(String path);
    protected abstract void writeOnStorage(byte[] data, String path);
    public abstract void replaceOnStorage(byte[] data, String path);

    protected String getRemoveFilePath() {
        return "/Trash";
    }

    @PreDestroy
    public void shutdown() {
        this.ignoreFetchFileFlagCache.clear();
        this.ignoreFetchFileFlagCache.shutdown();
    }
    
    @Override
    @Deprecated
    public List<T> getAll() {
        throw new UnsupportedOperationException("method getAll() should not be use");
    }

    public List<T> getAll(int userId) {
        return this.repositoryDao.findAllByOwnerUserId(userId);
    }

    public T getFileMetaDataById(I id) {
        var object = this.databaseCall.getAndExpire(id);
        if (ObjectUtils.isEmpty(object)) {
            HttpResponseThrowers.throwBadRequest(String.format("%s Id not found", this.T_TYPE.getSimpleName()));
        }

        return object;
    }

    public T getFileMetaDataByPath(String path) {
        return this.repositoryDao.findAllByPath(path)
                                 .parallelStream()
                                 .filter(metadata -> metadata.getPath().equals(path))
                                 .findFirst()
                                 .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private T getFileMetaDataByPathWithTry(String path, int userId, int numTry) {
        if (numTry >= 10)
            return (T) HttpResponseThrowers.throwNotFound("File not found");

        var metadata = this.getFileMetaDataByPath(path);

        if (!ObjectUtils.isEmpty(metadata)) {
            this.checkIsRelatedToUser(metadata, userId);
            return metadata;
        } else if (this.isFileExist(path)) {
            numTry++;
            return getFileMetaDataByPathWithTry(path, userId, numTry);
        } else
            return null;
    }

    public T getFileMetaDataByPath(String path, int userId) {
        path = this.formatPath(path);
        return getFileMetaDataByPathWithTry(path, userId, 0);
    }

    public T getFileMetaDataByName(String fileName, int userId) {
        return this.getFileMetaDataByPath(String.format("/%s/%s", userId, fileName), userId);
    }

    public T getFileByPath(String path) {
        var metadata = this.getFileMetaDataByPath(path);
        return ObjectUtils.isEmpty(metadata) ? null : this.processingGetOutput(metadata);
    }

    public T getFileByPath(String path, int userId) {
        return getFileByPathWithTry(path, userId, 0);
    }

    private T getFileByPathWithTry(String path, int userId, int numTry) {
        var metadata = this.getFileMetaDataByPathWithTry(path, userId, numTry);
        return ObjectUtils.isEmpty(metadata) ? null : this.processingGetOutput(metadata);
    }

    public T getFileByName(String fileName, int userId) {
        return this.getFileByPath(String.format("/%s/%s", userId, fileName), userId);
    }

    public T getFileMetaDataByCriteria(I id, String path, String fileName, int userId) {

        if (!ObjectUtils.isEmpty(id)) {
            var metadata = this.getFileMetaDataById(id);
            this.checkIsRelatedToUser(metadata, userId);
            return metadata;
        }

        if (!ObjectUtils.isEmpty(path)) {
            var metadata = this.getFileMetaDataByPath(path, userId);
            if (!ObjectUtils.isEmpty(metadata))
                return metadata;
        }

        if (!ObjectUtils.isEmpty(fileName)) {
            var metadata = this.getFileMetaDataByName(fileName, userId);
            if (!ObjectUtils.isEmpty(metadata))
                return metadata;
        }

        return null;
    }

    public T getFileByCriteria(I id, String path, String fileName, int userId) {
        var metadata = this.getFileMetaDataByCriteria(id, path, fileName, userId);
        return ObjectUtils.isEmpty(metadata) ? null : this.processingGetOutput(metadata);
    }

    public T patchFileMetaData(T originalMetadata, T newMetaData) {
        isOwnByUser(originalMetadata);
        String newName = newMetaData.getOriginalFilename();
        String originalPath = originalMetadata.getPath();
        int ownerId = originalMetadata.getOwnerUserId();
        String newPath = ObjectUtils.isEmpty(newName) ? null : String.format("/%s/%s", ownerId, newName);
        String newType = ObjectUtils.isEmpty(newName) ? null : this.getContentTypeFromPath(newPath);

        if (!ObjectUtils.isEmpty(newType) && newType != null && !newType.equals(originalMetadata.getContentType()))
            HttpResponseThrowers.throwBadRequest("New type can't be difference from old type");

        if (!ObjectUtils.isEmpty(newName) && this.isFileExist(newPath))
            HttpResponseThrowers.throwBadRequest("New file name or path already exist");

        newMetaData.setOriginalFilename(newName);
        newMetaData.setPath(newPath);
        ReflectionUtils.patchValue(originalMetadata, newMetaData);
        originalMetadata.setOwnerUserId(ownerId);

        if (!ObjectUtils.isEmpty(newName)) {
            this.moveFileOnStorage(originalPath, newPath);
        }

        return this.databaseCall.saveAndExpire(originalMetadata);
    }

    @SuppressWarnings("unchecked")
    public void delete(T fileMetaData) {
        if(this.isFileExistOnStorage(fileMetaData.getPath())) {
            var ownerId = this.getOwnerUserIdFromPath(fileMetaData.getPath());
            var moveFilePath = String.format("%s/%s/%s", this.getRemoveFilePath(), ownerId, fileMetaData.getOriginalFilename());
            
            this.checkIfFileDirectoryExist(moveFilePath);
            
            var toMoveFilePath = moveFilePath;
            int count = 0;
            while(this.isFileExistOnStorage(toMoveFilePath)) {
                count++;
                toMoveFilePath = addCountToFilePath(moveFilePath, count);
            }
            
            this.moveFileOnStorage(fileMetaData.getPath(), toMoveFilePath);
            super.delete((I) ReflectionUtils.getIdFieldValue(fileMetaData));
        }
        else {
            super.delete((I) ReflectionUtils.getIdFieldValue(fileMetaData));
            return;
        }
    }

    @Override
    public void delete(I id) {
        var fileMetaData = this.getFileMetaDataById(id);
        this.delete(fileMetaData);
    }

    private String addCountToFilePath(String path, int count) {
        List<String> splits = new ArrayList<>();
        splits.addAll(Arrays.stream(path.split("\\.")).toList());

        if(splits.size() >= 2) {
            int index = splits.size() - 2;
            splits.set(index, String.format("%s (%s)", splits.get(index), count));
        }

        return splits.stream().collect(Collectors.joining("."));
    }

    public boolean isFileExist(String path) {
        var exist = this.isFileExistOnStorage(path);
        var metaData = this.getFileMetaDataByPath(path);

        if (exist && metaData == null) {
            var data = this.readRawOnStorage(path);
            var contentType = this.getContentTypeFromPath(path);
            var fileName = this.getFileNameFromPath(path);
            var userId = this.getOwnerUserIdFromPath(path);
            long size = data.length;
            var metadata = this.newEmptyObject();
            metadata.setPublicity(false);
            metadata.setContentType(contentType);
            metadata.setOriginalFilename(fileName);
            metadata.setPath(path);
            metadata.setSize(size);
            metadata.setOwnerUserId(userId);
            this.databaseCall.saveAndExpire(metadata);
        }
        else if (!exist && metaData != null) {
            this.delete(metaData);
        }

        return exist;
    }

    public String getFileNameFromPath(String path) {
        var splits = path.split("/");
        return splits[splits.length - 1];
    }

    public int getOwnerUserIdFromPath(String path) {
        var splits = path.split("/");
        return Integer.parseInt(splits[1]);
    }

    public String getContentTypeFromPath(String path) {
        try {
            return Files.probeContentType(new File(getFileNameFromPath(path)).toPath());
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    public boolean isFileExist(String fileName, int userId) {
        String path = String.format("/%s/%s", userId, fileName);
        return isFileExist(path);
    }

    @Override
    public boolean isRelatedToUser(T fileMetaData, int userId, List<UserPermissionEnum> userPermissions) {
        if(fileMetaData.getPublicity() != null && fileMetaData.getPublicity() == true)
            return true;
        else
            return super.isRelatedToUser(fileMetaData, userId, userPermissions);

    }

    public String getDirectoryPathFromFilePath(String filePath) {
        List<String> splits = Arrays.stream(filePath.split("/")).toList();
        String lastElement = splits.get(splits.size() - 1);
        if(lastElement.contains("."))
            splits = splits.subList(0, splits.size() - 1);
        
        return splits.stream().collect(Collectors.joining("/"));
    }

    public byte[] getRawData(T fileMetaData) {
        return this.readRawOnStorage(fileMetaData.getPath());
    }

    @Override
    protected T processingGetOutput(T object) {
        if(!ObjectUtils.isEmpty(object)) {
            if(!this.isFileExistOnStorage(object.getPath())) {
                this.delete(object);
                HttpResponseThrowers.throwNotFound("File not found");
            }   
            else {
                if(!this.ignoreFetchFileFlagCache.contains(this.getUniqueKey(object)))
                    object.setData(getRawData(object));
            }
        }

        return object;
    }

    @Override
    protected T processingPostInput(T object) {
        if (ObjectUtils.isEmpty(object.getData()))
            HttpResponseThrowers.throwBadRequest("File is empty");

        if (this.isFileExist(object.getPath()))
            HttpResponseThrowers.throwBadRequest("File name is already exist");

        //ignore fetch raw data when get
        this.ignoreFetchFileFlagCache.add(this.getUniqueKey(object));

        return object;
    }

    @Override
    protected T processingPostOutput(T object) {
        this.checkIfFileDirectoryExist(object.getPath());
        var data = object.getData();
        this.writeOnStorage(data, object.getPath());

        //remove ignore fetch raw data when get
        this.ignoreFetchFileFlagCache.remove(this.getUniqueKey(object));
        
        return object;
    }

    @Override
    protected T processingPutInput(I id, T input) {
        if (ObjectUtils.isEmpty(input.getData()))
            HttpResponseThrowers.throwBadRequest("File is empty");

        if (!this.isFileExist(input.getPath()))
            HttpResponseThrowers.throwBadRequest("File not found");

        //ignore fetch raw data when get
        this.ignoreFetchFileFlagCache.add(this.getUniqueKey(input));

        return input;
    }

    @Override
    protected T processingPutOutput(I id, T output) {
        //remove ignore fetch raw data when get
        this.ignoreFetchFileFlagCache.remove(this.getUniqueKey(output));

        return super.processingPutOutput(id, output);
    }

    @Override
    protected T processingPatchInput(I id, T input) {
        //ignore fetch raw data when get
        this.ignoreFetchFileFlagCache.add(this.getUniqueKey(input));

        return super.processingPatchInput(id, input);
    }

    @Override
    protected T processingPatchOutput(I id, T output) {
        //remove ignore fetch raw data when get
        this.ignoreFetchFileFlagCache.remove(this.getUniqueKey(output));

        return super.processingPatchOutput(id, output);
    }

    protected T getUniqueKey(T fileMetaData) {
        var temp = this.newEmptyObject();
        temp.setOriginalFilename(fileMetaData.getOriginalFilename());
        temp.setPath(fileMetaData.getPath());
        return temp;
    }

    private String formatPath(String path) {
        // Replace all backslashes with forward slashes
        if (path.contains("\\")) {
            path = path.replaceAll("\\\\", "/"); // Use "\\\\" to match a literal backslash
        }

        // Replace multiple slashes with a single slash and remove trailing slash if any
        path = path.replaceAll("/+", "/").replaceAll("/$", "");

        // Ensure the path starts with a single leading slash
        path = path.startsWith("/") ? path : String.format("/%s", path);

        return path;
    }

    public T formatMetaData(T fileMetaData) {
        fileMetaData.setPath(this.formatPath(fileMetaData.getPath()));
        var fileName = this.formatPath(fileMetaData.getOriginalFilename());
        fileName = this.getFileNameFromPath(fileName);
        fileMetaData.setOriginalFilename(fileName);
        return fileMetaData;
    }
}
