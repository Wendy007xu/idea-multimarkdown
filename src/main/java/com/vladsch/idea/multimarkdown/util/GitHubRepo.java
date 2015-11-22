
/*
 * Copyright 2013 Square, Inc.
 * No license information provided
 *
 * Copyright (c) 2015-2015 Vladimir Schneider <vladimir.schneider@gmail.com>, all rights reserved.
 *
 * This code is based on code from https://github.com/jawspeak/intellij-plugin-copy-and-open-github-url
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package com.vladsch.idea.multimarkdown.util;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.vladsch.idea.multimarkdown.MultiMarkdownProjectComponent;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 */
public class GitHubRepo {
    private static final Logger logger = org.apache.log4j.Logger.getLogger(GitHubRepo.class);
    final private static Pattern INI_CATEGORY = Pattern.compile("\\[\\s*(\\w+)[\\s'\"]+(\\w+)[\\s'\"]+\\]");
    final private static Pattern URL_VALUE = Pattern.compile("\\s*url\\s*=\\s*([^\\s]*)\\.git");
    final protected static String GIT_CONFIG = "config";

    @NotNull protected final MultiMarkdownProjectComponent projectComponent;
    @NotNull protected final String gitHubBaseUrl;
    @NotNull private final String basePath;
    private final boolean isWiki;

    @VisibleForTesting
    protected GitHubRepo(@NotNull MultiMarkdownProjectComponent projectComponent, @NotNull String gitHubBaseUrl, @NotNull String basePath) {
        this.projectComponent = projectComponent;
        this.isWiki = FilePathInfo.isWikiHomeDir(basePath);
        this.gitHubBaseUrl = gitHubBaseUrl;
        this.basePath = basePath;
    }

    public boolean isWiki() {
        return isWiki;
    }

    @NotNull
    public String getBasePath() {
        return basePath;
    }

    @Nullable
    public String getRelativePath(@Nullable String path) {
        if (path != null && path.startsWith(basePath)) {
            return FilePathInfo.removeStart(path.substring(basePath.length()), '/');
        }
        return null;
    }

    public String repoUrlFor(String relativeFilePath) {
        return repoUrlFor(relativeFilePath, null);
    }

    public String repoUrlFor(@NotNull VirtualFile virtualFile, boolean withExtension, @Nullable String anchor) {
        FilePathInfo pathInfo = new FilePathInfo(virtualFile);
        String relativePath = getRelativePath(withExtension ? pathInfo.getFilePathWithAnchor() : pathInfo.getFilePathWithAnchorNoExt());
        return relativePath == null ? null : repoUrlFor(relativePath, anchor);
    }

    public String repoUrlFor(@NotNull String relativeFilePath, @Nullable String anchor) {
        if (isWiki()) {
            relativeFilePath = FilePathInfo.removeStart(relativeFilePath, "../../wiki");
        }

        return FilePathInfo.endWith(gitHubBaseUrl(), '/') + (isWiki() ? "wiki/" : "blob/master/") + FilePathInfo.asLinkRefURL(FilePathInfo.removeStart(relativeFilePath, "./")) + FilePathInfo.startWith(anchor, '#');
    }

    @NotNull
    public String gitHubBaseUrl() {
        return gitHubBaseUrl;
    }

    @Nullable
    protected static String getGitPath(@NotNull String filePath) {
        FilePathInfo filePathInfo = new FilePathInfo(filePath).append(".git");
        File gitFile = new File(filePathInfo.getFullFilePath());
        String gitPath = null;

        if (gitFile.exists()) {
            if (gitFile.isFile()) {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new FileReader(gitFile));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // gitdir: ../.git/modules/laravel-translation-manager.isWiki
                        if (line.startsWith("gitdir:")) {
                            line = line.substring("gitdir:".length()).trim();
                            FilePathInfo configInfo = filePathInfo.resolveLinkRef(line, false, false);
                            if (configInfo != null) {
                                gitPath = configInfo.getFullFilePath();
                            }

                            break;
                        }
                    }
                } catch (IOException ignored) {
                    logger.info("Could not read " + gitFile, ignored);
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            } else {
                return filePathInfo.getFullFilePath();
            }
        }
        return gitPath;
    }

    @Nullable
    protected static String gitHubBaseUrl(@NotNull File gitConfigFile) {
        String baseUrl = null;

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(gitConfigFile));
            String line;
            boolean inRemoteOriginSection = false;
            while ((line = reader.readLine()) != null) {
                if (line.matches("\\s*#")) continue;
                Matcher matcher = INI_CATEGORY.matcher(line);
                if (matcher.matches()) {
                    inRemoteOriginSection = "remote".equals(matcher.group(1))
                            && "origin".equals(matcher.group(2));
                    continue;
                }
                matcher = URL_VALUE.matcher(line);
                if (inRemoteOriginSection && matcher.matches()) {
                    baseUrl = "https://" + matcher.group(1)
                            .replaceAll("git://|git@|https://", "")
                            .replaceAll(":", "/");

                    FilePathInfo baseUrlInfo = new FilePathInfo(baseUrl);
                    if (baseUrlInfo.isWikiHomeDir()) {
                        baseUrl = baseUrlInfo.getFilePathNoExt();
                    }
                    break;
                }
            }
        } catch (IOException ignored) {
            logger.info("No remote origin in " + gitConfigFile, ignored);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return baseUrl;
    }

    @Nullable
    public static GitHubRepo getGitHubRepo(@NotNull MultiMarkdownProjectComponent projectComponent, @Nullable String path, @Nullable String basePath) {
        return getGitHubRepo(projectComponent, path, basePath, null);
    }

    @Nullable
    public static GitHubRepo getGitHubRepo(@NotNull MultiMarkdownProjectComponent projectComponent, @Nullable String path, @Nullable String basePath, @Nullable String gitConfig) {
        if (path == null || basePath == null) return null;

        String nextPath = path;
        do {
            FilePathInfo pathInfo = new FilePathInfo(nextPath);

            String gitPath = getGitPath(pathInfo.getFullFilePath());
            if (gitPath != null) {
                File gitConfigFile = new File(gitPath, gitConfig != null ? gitConfig : GIT_CONFIG);
                if (gitConfigFile.exists() && gitConfigFile.isFile()) {
                    String baseUrl = gitHubBaseUrl(gitConfigFile);
                    if (baseUrl != null) {
                        return new GitHubRepo(projectComponent, baseUrl, pathInfo.getFullFilePath());
                    }

                    // this sub-module does not have a remote.
                    return null;
                }
            }

            nextPath = pathInfo.getPath();
        } while (!nextPath.isEmpty() && !nextPath.equals("/") && !nextPath.equalsIgnoreCase(basePath));

        return null;
    }

}