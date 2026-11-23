package com.example.swiftsim.files;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FilePublicationRepository extends JpaRepository<FilePublication, Long> {
    Optional<FilePublication> findByMessageId(Long messageId);

    boolean existsBySha256(String sha256);

    List<FilePublication> findByStatusIn(Collection<String> statuses);

    List<FilePublication> findTop10ByOrderByIdDesc();
}
