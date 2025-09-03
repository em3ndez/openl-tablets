package org.openl.rules.project.impl.local;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openl.rules.repository.api.ChangesetType;
import org.openl.rules.repository.api.Features;
import org.openl.rules.repository.api.FeaturesBuilder;
import org.openl.rules.repository.api.FileData;
import org.openl.rules.repository.api.FileItem;
import org.openl.rules.repository.api.UserInfo;
import org.openl.rules.repository.file.FileSystemRepository;
import org.openl.rules.workspace.dtr.impl.FileMappingData;
import org.openl.rules.workspace.lw.impl.FolderHelper;
import org.openl.util.FileUtils;
import org.openl.util.PropertiesUtils;
import org.openl.util.StringUtils;

public class LocalRepository extends FileSystemRepository {
    /**
     * @deprecated Will be removed in the future.
     */
    @Deprecated
    private static final String DATE_FORMAT = "yyyy-MM-dd";
    private static final String REPOSITORY_ID = "repository-id";
    private static final String PATH_IN_REPOSITORY = "path-in-repository";
    private static final String VERSION_PROPERTY = "version";
    private static final String BRANCH_PROPERTY = "branch";
    private static final String AUTHOR_PROPERTY = "author";
    /**
     * @deprecated Will be removed in the future. Is replaced with {@link #MODIFIED_AT_LONG_PROPERTY}.
     */
    @Deprecated
    private static final String MODIFIED_AT_PROPERTY = "modified-at";
    private static final String MODIFIED_AT_LONG_PROPERTY = "modified-at-long";
    private static final String SIZE_PROPERTY = "size";
    private static final String COMMENT_PROPERTY = "comment";
    private static final String UNIQUE_ID_PROPERTY = "unique-id";
    private static final String FILE_MODIFIED_PROPERTY = "modified";
    private static final String FILE_PROPERTIES_FOLDER = "file-properties";

    private final Logger log = LoggerFactory.getLogger(LocalRepository.class);
    private final PropertiesEngine propertiesEngine;

    public LocalRepository(Path location) {
        setRoot(location);
        propertiesEngine = new PropertiesEngine(location.toFile());
    }

    @Override
    public List<FileData> list(String path) throws IOException {
        List<FileData> list = super.list(path);

        // Property and history files must be hidden
        list.removeIf(fileData -> isShouldBeHidden(fileData.getName(), path));

        return list;
    }

    private boolean isShouldBeHidden(String fileName, String path) {
        if (!path.endsWith("/")) {
            path += "/";
        }
        return fileName.startsWith(path + FolderHelper.PROPERTIES_FOLDER + "/") || fileName
                .startsWith(path + FolderHelper.HISTORY_FOLDER + "/");
    }

    @Override
    public FileData save(FileData data, InputStream stream) throws IOException {
        FileData fileData = super.save(data, stream);
        notifyModified(data.getName());
        return fileData;
    }

    @Override
    public List<FileData> save(List<FileItem> fileItems) throws IOException {
        List<FileData> result = super.save(fileItems);
        for (FileData data : result) {
            notifyModified(data.getName());
        }
        return result;
    }

    @Override
    public FileData save(FileData folderData,
                         final Iterable<FileItem> files,
                         ChangesetType changesetType) throws IOException {
        FileData fileData = super.save(folderData, files, changesetType);
        notifyModified(folderData.getName());
        return fileData;
    }

    @Override
    public boolean delete(FileData data) throws IOException {
        boolean deleted = super.delete(data);
        deleteFileProperties(data.getName());

        if (deleted) {
            // If name doesn't contain "/", it's a project name. No need to recreate project state for the deleted
            // project.
            String name = data.getName();
            if (name.contains("/")) {
                notifyModified(name);
            }
        }
        return deleted;
    }

    @Override
    public Features supports() {
        return new FeaturesBuilder(this).setSupportsUniqueFileId(true).setVersions(false).setFolders(true).build();
    }

    @Override
    protected FileData getFileData(Path file) throws IOException {
        FileData fileData = super.getFileData(file);
        var properties = readFileProperties(fileData.getName());
        String uniqueId = properties.get(UNIQUE_ID_PROPERTY);
        if (uniqueId != null) {
            // If the file is modified, set unique id to null to mark that it's id is unknown
            if (isFileModified(fileData, properties)) {
                uniqueId = null;
            }
            fileData.setUniqueId(uniqueId);
        }

        return fileData;
    }

    /**
     * The file is modified if any of these is true: a) it's marked as modified in properties file b) size is changed c)
     * last modified time is changed
     *
     * @param fileData   file data for checking file
     * @param properties properties of original file
     * @return true if file is modified
     */
    private boolean isFileModified(FileData fileData, Map<String, String> properties) {
        boolean modified = Boolean.parseBoolean(properties.get(FILE_MODIFIED_PROPERTY));
        if (modified) {
            return true;
        }

        try {
            long size = Long.parseLong(properties.get(SIZE_PROPERTY));
            if (fileData.getSize() != size) {
                return true;
            }
        } catch (NumberFormatException ignored) {
            // Cannot determine saved size. So treat it as modified file
            return true;
        }

        try {
            Date modifiedAt = new Date(Long.parseLong(properties.get(MODIFIED_AT_LONG_PROPERTY)));
            return !modifiedAt.equals(fileData.getModifiedAt());
        } catch (NumberFormatException ignored) {
            // Cannot determine saved date. So treat it as modified file
            return true;
        }
    }

    @Override
    protected boolean isSkip(Path file) {
        return FolderHelper.PROPERTIES_FOLDER.equals(file.getFileName().toString());
    }

    public ProjectState getProjectState(final String pathInProject) {
        return new ProjectState() {
            private static final String MODIFIED_FILE_NAME = ".modified";
            private static final String VERSION_FILE_NAME = ".version";

            @Override
            public void notifyModified() {
                propertiesEngine.createPropertiesFile(pathInProject, MODIFIED_FILE_NAME);
                setFileModified(pathInProject);
                invokeListener();
            }

            @Override
            public boolean isModified() {
                return propertiesEngine.getPropertiesFile(pathInProject, MODIFIED_FILE_NAME).exists();
            }

            @Override
            public void clearModifyStatus() {
                propertiesEngine.deletePropertiesFile(pathInProject, MODIFIED_FILE_NAME);
                File propertiesFolder = propertiesEngine.getPropertiesFolder(pathInProject);
                File[] files = new File(propertiesFolder, FILE_PROPERTIES_FOLDER).listFiles();
                clearFileModifyStatus(files);
            }

            private void clearFileModifyStatus(File[] files) {
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile()) {
                            var properties = new LinkedHashMap<String, String>();
                            try {
                                PropertiesUtils.load(file.toPath(), properties::put);
                            } catch (IOException e) {
                                log.error(e.getMessage(), e);
                            }

                            properties.remove(FILE_MODIFIED_PROPERTY);

                            try {
                                PropertiesUtils.store(file.toPath(), properties.entrySet());
                            } catch (IOException e) {
                                log.error(e.getMessage(), e);
                            }
                        } else if (file.isDirectory()) {
                            clearFileModifyStatus(file.listFiles());
                        }
                    }
                }
            }

            @Override
            public void setProjectVersion(String version) {
                if (version == null) {
                    propertiesEngine.deletePropertiesFile(pathInProject, VERSION_FILE_NAME);
                    return;
                }

                File file = propertiesEngine.createPropertiesFile(pathInProject, VERSION_FILE_NAME);

                var properties = new LinkedHashMap<String, String>();
                properties.put(VERSION_PROPERTY, version);
                try {
                    PropertiesUtils.store(file.toPath(), properties.entrySet());
                } catch (IOException e) {
                    throw new IllegalStateException(version);
                }
            }

            @Override
            public String getProjectVersion() {
                return getProperty(VERSION_PROPERTY, VERSION_FILE_NAME, pathInProject);
            }

            @Override
            public String getRepositoryId() {
                return getProperty(REPOSITORY_ID, VERSION_FILE_NAME, pathInProject);
            }

            @Override
            public void saveFileData(String repositoryId, FileData fileData) {
                if (fileData.getVersion() == null || fileData.getAuthor() == null || fileData.getAuthor()
                        .getName() == null || fileData.getModifiedAt() == null) {
                    // No need to save empty fileData
                    return;
                }
                var properties = new LinkedHashMap<String, String>();
                properties.put(REPOSITORY_ID, repositoryId);
                FileMappingData mappingData = fileData.getAdditionalData(FileMappingData.class);
                if (mappingData != null) {
                    properties.put(PATH_IN_REPOSITORY, mappingData.getInternalPath());
                }
                String name = Optional.ofNullable(fileData.getAuthor()).map(UserInfo::getName).orElse(null);
                properties.put(VERSION_PROPERTY, fileData.getVersion());
                properties.put(AUTHOR_PROPERTY, name);
                properties.put(MODIFIED_AT_PROPERTY,
                        new SimpleDateFormat(DATE_FORMAT).format(fileData.getModifiedAt()));
                properties.put(MODIFIED_AT_LONG_PROPERTY, "" + fileData.getModifiedAt().getTime());
                properties.put(SIZE_PROPERTY, "" + fileData.getSize());
                if (fileData.getComment() != null) {
                    properties.put(COMMENT_PROPERTY, fileData.getComment());
                }
                String branch = fileData.getBranch();
                if (branch != null) {
                    properties.put(BRANCH_PROPERTY, branch);
                }
                File file = propertiesEngine.createPropertiesFile(pathInProject, VERSION_FILE_NAME);
                try {
                    PropertiesUtils.store(file.toPath(), properties.entrySet());
                } catch (IOException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }

            @Override
            public FileData getFileData() {
                File file = propertiesEngine.getPropertiesFile(pathInProject, VERSION_FILE_NAME);
                if (!file.exists()) {
                    return null;
                }

                var properties = new HashMap<String, String>();
                try {
                    PropertiesUtils.load(file.toPath(), properties::put);
                    FileData fileData = new FileData();
                    File projectFolder = propertiesEngine.getProjectFolder(pathInProject);

                    String name = projectFolder.getName();
                    String version = properties.get(VERSION_PROPERTY);
                    String branch = properties.get(BRANCH_PROPERTY);
                    String author = properties.get(AUTHOR_PROPERTY);
                    String pathIntRepository = properties.get(PATH_IN_REPOSITORY);

                    if (pathIntRepository != null) {
                        fileData.addAdditionalData(new FileMappingData(name, pathIntRepository));
                    }

                    Date modifiedAt;
                    String modifiedAtLong = properties.get(MODIFIED_AT_LONG_PROPERTY);
                    if (modifiedAtLong != null) {
                        modifiedAt = new Date(Long.parseLong(modifiedAtLong));
                    } else {
                        // Backward compatibility for projects opened in previous version of OpenL Studio.
                        // Will be removed in the future.
                        String modifiedAtStr = properties.get(MODIFIED_AT_PROPERTY);
                        modifiedAt = modifiedAtStr == null ? null
                                : new SimpleDateFormat(DATE_FORMAT).parse(modifiedAtStr);
                    }
                    String size = properties.get(SIZE_PROPERTY);
                    String comment = properties.get(COMMENT_PROPERTY);

                    if (version == null || author == null || modifiedAt == null) {
                        // Only partial information is available. Cannot fill FileData. Must request from repository.
                        return null;
                    }

                    fileData.setName(name);
                    fileData.setVersion(version);
                    fileData.setBranch(branch);
                    fileData.setAuthor(new UserInfo(author));
                    fileData.setModifiedAt(modifiedAt);
                    fileData.setSize(Long.parseLong(size));
                    fileData.setComment(comment);

                    return fileData;
                } catch (IOException | ParseException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private String getProperty(String prop, String fileName, String pathInProject) {
        File file = propertiesEngine.getPropertiesFile(pathInProject, fileName);
        if (!file.exists()) {
            return null;
        }

        var properties = new LinkedHashMap<String, String>();
        try {
            PropertiesUtils.load(file.toPath(), properties::put);
            return properties.get(prop);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void notifyModified(String path) {
        getProjectState(path).notifyModified();
    }

    private String getFilePropertiesPath(String path) {
        String relativePath = path;
        if (new File(relativePath).isAbsolute()) {
            relativePath = propertiesEngine.getRelativePath(path).replace(File.separatorChar, '/');
        }
        if (!relativePath.contains("/")) {
            // Not a file. Just project name
            return "";
        }
        String pathInProject = relativePath.substring(relativePath.indexOf('/'));
        return FILE_PROPERTIES_FOLDER + pathInProject;
    }

    private void setFileModified(String path) {
        var properties = readFileProperties(path);
        properties.put(FILE_MODIFIED_PROPERTY, "true");

        String filePropertiesPath = getFilePropertiesPath(path);
        if (StringUtils.isNotEmpty(filePropertiesPath)) {
            File file = propertiesEngine.createPropertiesFile(path, filePropertiesPath);
            try {
                PropertiesUtils.store(file.toPath(), properties.entrySet());
            } catch (IOException e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    public void updateFileProperties(FileData fileData) {
        String path = fileData.getName();
        String filePropertiesPath = getFilePropertiesPath(path);

        if (StringUtils.isNotEmpty(filePropertiesPath)) {
            var properties = readFileProperties(path);

            if (fileData.getUniqueId() != null) {
                properties.put(UNIQUE_ID_PROPERTY, fileData.getUniqueId());
            } else {
                properties.remove(UNIQUE_ID_PROPERTY);
            }
            properties.put(MODIFIED_AT_LONG_PROPERTY, "" + fileData.getModifiedAt().getTime());
            properties.put(SIZE_PROPERTY, "" + fileData.getSize());

            File file = propertiesEngine.createPropertiesFile(path, filePropertiesPath);
            try {
                PropertiesUtils.store(file.toPath(), properties.entrySet());
            } catch (IOException e) {
                log.error(e.getMessage(), e);
                FileUtils.deleteQuietly(file);
            }
        }
    }

    private Map<String, String> readFileProperties(String path) {
        var properties = new HashMap<String, String>();
        String filePropertiesPath = getFilePropertiesPath(path);
        if (filePropertiesPath.isEmpty()) {
            return properties;
        }

        File fileProperties = propertiesEngine.getPropertiesFile(path, filePropertiesPath);

        if (fileProperties.exists()) {
            try {
                PropertiesUtils.load(fileProperties.toPath(), properties::put);
            } catch (IOException e) {
                log.error(e.getMessage(), e);
            }
        }
        return properties;
    }

    public void deleteAllFileProperties(String path) {
        File propertiesFolder = propertiesEngine.getPropertiesFolder(path);
        File fileProps = new File(propertiesFolder, FILE_PROPERTIES_FOLDER);
        FileUtils.deleteQuietly(fileProps);
    }

    private void deleteFileProperties(String path) {
        String filePropertiesPath = getFilePropertiesPath(path);
        if (StringUtils.isNotEmpty(filePropertiesPath)) {
            File fileProperties = propertiesEngine.getPropertiesFile(path, filePropertiesPath);
            if (fileProperties.isFile()) {
                FileUtils.deleteQuietly(fileProperties);
            }
        }
    }

}
