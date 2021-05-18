package org.ohdsi.webapi.versioning.service;

import org.ohdsi.webapi.exception.AtlasException;
import org.ohdsi.webapi.service.AbstractDaoService;
import org.ohdsi.webapi.versioning.domain.AssetVersionBase;
import org.ohdsi.webapi.versioning.domain.AssetVersion;
import org.ohdsi.webapi.versioning.domain.AssetVersionType;
import org.ohdsi.webapi.versioning.dto.AssetVersionUpdateDTO;
import org.ohdsi.webapi.versioning.repository.CharacterizationVersionRepository;
import org.ohdsi.webapi.versioning.repository.CohortVersionRepository;
import org.ohdsi.webapi.versioning.repository.ConceptSetVersionRepository;
import org.ohdsi.webapi.versioning.repository.IrVersionRepository;
import org.ohdsi.webapi.versioning.repository.PathwayVersionRepository;
import org.ohdsi.webapi.versioning.repository.VersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;
import javax.ws.rs.NotFoundException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional
public class VersionService<T extends AssetVersion> extends AbstractDaoService {
    @Value("${versioning.maxAttempt}")
    private int maxAttempt;

    private static final Logger logger = LoggerFactory.getLogger(VersionService.class);
    private final EntityManager entityManager;
    private final Map<AssetVersionType, VersionRepository<T>> repositoryMap;

    @Autowired
    private VersionService<T> versionService;

    @Autowired
    public VersionService(
            EntityManager entityManager,
            CohortVersionRepository cohortRepository,
            ConceptSetVersionRepository conceptSetVersionRepository,
            CharacterizationVersionRepository characterizationVersionRepository,
            IrVersionRepository irRepository,
            PathwayVersionRepository pathwayRepository) {
        this.entityManager = entityManager;

        this.repositoryMap = new HashMap<>();
        this.repositoryMap.put(AssetVersionType.COHORT, (VersionRepository<T>) cohortRepository);
        this.repositoryMap.put(AssetVersionType.CONCEPT_SET, (VersionRepository<T>) conceptSetVersionRepository);
        this.repositoryMap.put(AssetVersionType.CHARACTERIZATION, (VersionRepository<T>) characterizationVersionRepository);
        this.repositoryMap.put(AssetVersionType.INCIDENCE_RATE, (VersionRepository<T>) irRepository);
        this.repositoryMap.put(AssetVersionType.PATHWAY, (VersionRepository<T>) pathwayRepository);
    }

    private VersionRepository<T> getRepository(AssetVersionType type) {
        return repositoryMap.get(type);
    }

    public List<AssetVersionBase> getVersions(AssetVersionType type, int assetId) {
        return getRepository(type).findAllVersions(assetId);
    }

    public T create(AssetVersionType type, T assetVersion) {
        assetVersion.setCreatedBy(getCurrentUser());
        assetVersion.setCreatedDate(new Date());

        int attemptsCounter = 0;
        boolean saved = false;
        // Trying to save current version. Current version is selected from database
        // If current version number is used - get latest version from database again and try to save.
        while (!saved && attemptsCounter < maxAttempt) {
            attemptsCounter++;

            Integer latestVersion = getRepository(type).getLatestVersion(assetVersion.getAssetId());
            if (Objects.nonNull(latestVersion)) {
                assetVersion.setVersion(latestVersion + 1);
            }

            try {
                assetVersion = versionService.save(type, assetVersion);
                saved = true;
            } catch (PersistenceException e) {
                logger.warn("Error during saving version", e);
            }
        }
        if (!saved) {
            log.error("Error during saving version");
            throw new AtlasException("Error during saving version");
        }
        return assetVersion;
    }

    public T update(AssetVersionType type, AssetVersionUpdateDTO updateDTO) {
        T currentVersion = getRepository(type).findOne(updateDTO.getId());
        if (Objects.isNull(currentVersion)) {
            throw new NotFoundException("Version not found");
        }
        checkOwnerOrAdmin(currentVersion.getCreatedBy());

        currentVersion.setComment(updateDTO.getComment());
        currentVersion.setArchived(updateDTO.isArchived());
        return save(type, currentVersion);
    }

    public void delete(AssetVersionType type, Long id) {
        T currentVersion = getRepository(type).findOne(id);
        if (Objects.isNull(currentVersion)) {
            throw new NotFoundException("Version not found");
        }
        checkOwnerOrAdmin(currentVersion.getCreatedBy());
        currentVersion.setArchived(true);
    }

    public T getById(AssetVersionType type, Long id) {
        return getRepository(type).findOne(id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public T save(AssetVersionType type, T version) {
        version = getRepository(type).saveAndFlush(version);
        entityManager.refresh(version);
        return getRepository(type).findOne(version.getId());
    }
}
