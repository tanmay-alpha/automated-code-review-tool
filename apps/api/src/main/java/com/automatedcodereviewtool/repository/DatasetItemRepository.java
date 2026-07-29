package com.automatedcodereviewtool.repository;

import com.automatedcodereviewtool.entity.DatasetItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DatasetItemRepository extends JpaRepository<DatasetItem, DatasetItem.DatasetItemId> {
}