package com.automatedcodereviewtool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * A single row of the {@code ml.dataset_items} table — one
 * code_sample assigned to a dataset version with a split.
 */
@Entity
@Table(name = "dataset_items", schema = "ml")
public class DatasetItem {

    @EmbeddedId
    private DatasetItemId id;

    @Column(name = "split", nullable = false, length = 10)
    private String split;

    @Column(name = "group_key", nullable = false, length = 255)
    private String groupKey;

    @Column(name = "labels_snapshot", nullable = false, columnDefinition = "JSONB")
    private String labelsSnapshot;

    public DatasetItemId getId() { return id; }
    public void setId(DatasetItemId id) { this.id = id; }
    public String getSplit() { return split; }
    public void setSplit(String split) { this.split = split; }
    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String groupKey) { this.groupKey = groupKey; }
    public String getLabelsSnapshot() { return labelsSnapshot; }
    public void setLabelsSnapshot(String labelsSnapshot) { this.labelsSnapshot = labelsSnapshot; }

    @Embeddable
    public static class DatasetItemId implements Serializable {
        @Column(name = "dataset_version_id", nullable = false)
        private UUID datasetVersionId;

        @Column(name = "code_sample_id", nullable = false)
        private UUID codeSampleId;

        public DatasetItemId() {}
        public DatasetItemId(UUID datasetVersionId, UUID codeSampleId) {
            this.datasetVersionId = datasetVersionId;
            this.codeSampleId = codeSampleId;
        }
        public UUID getDatasetVersionId() { return datasetVersionId; }
        public UUID getCodeSampleId() { return codeSampleId; }
        public void setDatasetVersionId(UUID datasetVersionId) { this.datasetVersionId = datasetVersionId; }
        public void setCodeSampleId(UUID codeSampleId) { this.codeSampleId = codeSampleId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DatasetItemId other)) return false;
            return Objects.equals(datasetVersionId, other.datasetVersionId)
                && Objects.equals(codeSampleId, other.codeSampleId);
        }
        @Override
        public int hashCode() { return Objects.hash(datasetVersionId, codeSampleId); }
    }
}