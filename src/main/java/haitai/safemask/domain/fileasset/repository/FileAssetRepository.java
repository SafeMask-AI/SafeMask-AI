package haitai.safemask.domain.fileasset.repository;

import haitai.safemask.domain.fileasset.entity.FileAsset;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileAssetRepository extends JpaRepository<FileAsset, Long> {
}
