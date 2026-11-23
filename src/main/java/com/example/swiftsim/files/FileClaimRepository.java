package com.example.swiftsim.files;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FileClaimRepository extends JpaRepository<FileClaim, Long> {
    List<FileClaim> findByStatusIn(Collection<String> statuses);

    Optional<FileClaim> findFirstBySourcePathAndSha256AndStatusIn(String sourcePath, String sha256, Collection<String> statuses);

    Optional<FileClaim> findByInflightPath(String inflightPath);

    List<FileClaim> findTop20ByStatusInOrderByIdDesc(Collection<String> statuses);
}
