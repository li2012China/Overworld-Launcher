/*
 * Hello Minecraft! Launcher.
 * Copyright (C) 2013  huangyuhui <huanghongxun2008@126.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see {http://www.gnu.org/licenses/}.
 */
package org.jackhuang.hmcl.core.download;

import java.util.Collections;
import java.util.List;
import org.jackhuang.hmcl.core.install.InstallerType;
import org.jackhuang.hmcl.core.install.InstallerVersionList;

/**
 * Download source provider interface.
 * Each provider represents one download source (e.g. Mojang, BMCLAPI).
 * 
 * Phase 1 Enhancement (from PCL):
 * - Dynamic latency measurement
 * - Multi-source fallback support
 * - China mirror detection
 * - Priority-based source selection
 *
 * @author huangyuhui
 * @see <a href="https://github.com/Meloong-Git/PCL">PCL ModNet.vb DlSourceLoader</a>
 */
public abstract class IDownloadProvider {

    /**
     * Known provider IDs.
     */
    public static final String MOJANG = "mojang";
    public static final String BMCLAPI = "bmclapi";
    public static final String CURSE = "curse";

    /**
     * Whether this provider is a China-based mirror.
     * Used to optimize source selection based on user location.
     * 
     * @return true if this is a China mirror (e.g. BMCLAPI, Aliyun)
     */
    public boolean isChinaMirror() {
        return false;
    }

    /**
     * Priority of this provider (lower = higher priority).
     * When multiple providers are available, lower priority wins.
     * 
     * @return priority value, default 100
     */
    public int getPriority() {
        return 100;
    }

    /**
     * Unique identifier for this provider.
     * Used for source selection persistence.
     * 
     * @return provider ID string
     */
    public String getId() {
        return getClass().getSimpleName().replace("DownloadProvider", "").toLowerCase();
    }

    /**
     * Get the list of fallback providers when this source fails.
     * The first available provider in the list will be used.
     * 
     * @return list of fallback providers, empty by default
     */
    public List<IDownloadProvider> getFallbackProviders() {
        return Collections.emptyList();
    }

    /**
     * Check if this provider is currently available (responsive).
     * Used for health-check based source selection.
     * 
     * @return true if available, false otherwise
     */
    public boolean isAvailable() {
        return true;
    }

    public InstallerVersionList getInstallerByType(InstallerType type) {
        switch (type) {
        case Forge:
            return getForgeInstaller();
        case LiteLoader:
            return getLiteLoaderInstaller();
        case OptiFine:
            return getOptiFineInstaller();
        default:
            return null;
        }
    }

    public abstract InstallerVersionList getForgeInstaller();

    public abstract InstallerVersionList getLiteLoaderInstaller();

    public abstract InstallerVersionList getOptiFineInstaller();

    public abstract String getLibraryDownloadURL();

    public abstract String getVersionsDownloadURL();

    public abstract String getIndexesDownloadURL();

    public abstract String getVersionsListDownloadURL();

    public abstract String getAssetsDownloadURL();

    /**
     * For example, minecraft.json/assetIndex/url or
     * minecraft.json/downloads/client/url
     *
     * @param str baseURL
     *
     * @return parsedURL
     */
    public abstract String getParsedDownloadURL(String str);

    /**
     * Whether this provider allows using the original Mojang URL
     * when the file is not found on this mirror.
     *
     * @return true if original URL is allowed
     */
    public abstract boolean isAllowedToUseSelfURL();
}
