package com.viescloud.llc.object_storage_manager.dao;

import com.viescloud.llc.object_storage_manager.model.DatabaseFileMetaData;
import com.viescloud.llc.viesspringutils.dao.ViesUserAccessJpaRepositoryTemplate;

public interface DatabaseFileMetaDataDao extends ObjectStorageDao<DatabaseFileMetaData, Integer> {}
class DatabaseFileMetaDataDaoImpl extends ViesUserAccessJpaRepositoryTemplate<DatabaseFileMetaData> {}
