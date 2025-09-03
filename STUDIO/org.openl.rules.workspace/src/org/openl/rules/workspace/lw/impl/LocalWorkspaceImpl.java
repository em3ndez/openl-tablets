package org.openl.rules.workspace.lw.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.openl.rules.common.ProjectException;
import org.openl.rules.project.abstraction.AProject;
import org.openl.rules.project.impl.local.LocalRepository;
import org.openl.rules.project.impl.local.ProjectState;
import org.openl.rules.repository.api.FileData;
import org.openl.rules.repository.api.Repository;
import org.openl.rules.workspace.ProjectKey;
import org.openl.rules.workspace.dtr.DesignTimeRepository;
import org.openl.rules.workspace.dtr.FolderMapper;
import org.openl.rules.workspace.dtr.impl.FileMappingData;
import org.openl.rules.workspace.lw.LocalWorkspace;
import org.openl.rules.workspace.lw.LocalWorkspaceListener;

public class LocalWorkspaceImpl implements LocalWorkspace {
    private static final Comparator<AProject> PROJECTS_COMPARATOR = (o1, o2) -> o1.getName()
            .compareToIgnoreCase(o2.getName());

    private final String userId;
    private final File location;
    private final Map<ProjectKey, AProject> localProjects;
    private final List<LocalWorkspaceListener> listeners = new ArrayList<>();
    private final LocalRepository localRepository;
    private final DesignTimeRepository designTimeRepository;

    LocalWorkspaceImpl(String userId, File location, DesignTimeRepository designTimeRepository) {
        this.userId = userId;
        this.location = location;
        this.designTimeRepository = designTimeRepository;

        localProjects = new HashMap<>();

        localRepository = new LocalRepository(location.toPath());
        localRepository.initialize();
        loadProjects();
    }

    @Override
    public void addWorkspaceListener(LocalWorkspaceListener listener) {
        listeners.add(listener);
    }

    @Override
    public LocalRepository getRepository(String id) {
        if (id == null) {
            id = LOCAL_ID;
        }
        // Create a new instance with id and name.
        LocalRepository repository = new LocalRepository(localRepository.getRoot());
        repository.setId(id);
        if (designTimeRepository != null) {
            Repository designRepository = designTimeRepository.getRepository(id);
            if (designRepository != null) {
                repository.setName(designRepository.getName());
            }
        }
        repository.initialize();
        return repository;
    }

    @Override
    public File getLocation() {
        return location;
    }

    @Override
    public AProject getProject(String repositoryId, String name) throws ProjectException {
        AProject lp;
        synchronized (localProjects) {
            lp = localProjects.values()
                    .stream()
                    .filter(p -> (repositoryId == null || repositoryId.equals(p.getRepository().getId())) && p.getName()
                            .equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
        }
        if (lp == null) {
            throw new ProjectException("Cannot find project ''{0}''.", null, name);
        }

        return lp;
    }

    @Override
    public AProject getProjectForPath(String repositoryId, String path) {
        synchronized (localProjects) {
            return localProjects.get(new ProjectKey(repositoryId, path));
        }
    }

    @Override
    public Collection<AProject> getProjects() {
        synchronized (localProjects) {
            ArrayList<AProject> projects = new ArrayList<>(localProjects.values());
            projects.sort(PROJECTS_COMPARATOR);
            return projects;
        }
    }

    @Override
    public List<? extends AProject> getProjects(String repositoryId) {
        synchronized (localProjects) {
            return localProjects.entrySet()
                    .stream()
                    .filter(entry -> Objects.equals(repositoryId, entry.getKey().getRepositoryId()))
                    .map(Map.Entry::getValue)
                    .sorted(PROJECTS_COMPARATOR)
                    .collect(Collectors.toList());
        }
    }

    protected String getUserId() {
        return userId;
    }

    @Override
    public boolean hasProject(String repositoryId, String name) {
        synchronized (localProjects) {
            Optional<AProject> lp = localProjects.values()
                    .stream()
                    .filter(p -> (repositoryId == null || repositoryId.equals(p.getRepository().getId())) && p.getName()
                            .equalsIgnoreCase(name))
                    .findFirst();
            return lp.isPresent();
        }
    }

    private void loadProjects() {
        List<FileData> folders = localRepository.listFolders("");
        for (FileData folder : folders) {
            AProject lpi;
            String name = folder.getName();
            String repositoryPath = designTimeRepository.getRulesLocation() + name;
            ProjectState projectState = localRepository.getProjectState(name);
            LocalRepository repository = getRepository(projectState.getRepositoryId());
            FileData fileData = projectState.getFileData();
            if (fileData == null) {
                String version = projectState.getProjectVersion();
                lpi = new AProject(repository, name, version);
                repositoryPath = "<local-path>/" + name;
            } else {
                FileMappingData mappingData = fileData.getAdditionalData(FileMappingData.class);
                if (mappingData != null) {
                    repositoryPath = mappingData.getInternalPath();

                    String mappedName = name;
                    Repository designRepo = designTimeRepository.getRepository(repository.getId());
                    String rulesLocation = designTimeRepository.getRulesLocation();
                    if (designRepo != null && designRepo.supports().mappedFolders()) {
                        FolderMapper mapper = (FolderMapper) designRepo;
                        String mappedPath = mapper.findMappedName(repositoryPath);
                        if (mappedPath == null) {
                            mappedName = mapper.getMappedName(name, repositoryPath);
                        } else {
                            mappedName = mappedPath.startsWith(rulesLocation)
                                    ? mappedPath
                                    .substring(rulesLocation.length())
                                    : mappedPath;
                        }
                    }
                    mappingData.setExternalPath(rulesLocation + mappedName);
                }
                lpi = new AProject(repository, fileData);
            }
            localProjects.put(new ProjectKey(repository.getId(), repositoryPath), lpi);
        }
    }

    @Override
    public void refresh() {
        // check existing
        synchronized (localProjects) {
            localProjects.clear();
            loadProjects();
        }
    }

    @Override
    public void release() {
        synchronized (localProjects) {
            localProjects.clear();
        }

        localRepository.close();

        for (LocalWorkspaceListener lwl : new ArrayList<>(listeners)) {
            lwl.workspaceReleased(this);
        }
    }

    @Override
    public void removeWorkspaceListener(LocalWorkspaceListener listener) {
        listeners.remove(listener);
    }
}
