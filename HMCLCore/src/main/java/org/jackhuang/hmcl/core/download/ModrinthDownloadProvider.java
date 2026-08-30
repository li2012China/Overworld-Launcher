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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.jackhuang.hmcl.core.install.InstallerVersionList;

/**
 * Modrinth download provider.
 * 
 * Phase 3B Enhancement (from PCL: ModDownload.vb DlSourceModGet()):
 * - Modrinth API: https://api.modrinth.com/v2
 * - China mirror: mod.mcimirror.top/modrinth (PCL uses this as fallback)
 * - Priority: international, used when user sets source to Curse/Modrinth
 * 
 * @author huangyuhui
 */
public class ModrinthDownloadProvider extends IDownloadProvider {

    /** Primary API endpoint */
    private static final String MODRINTH_API = "https://api.modrinth.com/v2";
    /** China mirror (mirrors modrinth API) - from PCL: mod.mcimirror.top */
    private static final String CHINA_MIRROR = "https://mod.mcimirror.top/modrinth/v2";

    /** Whether to use the China mirror instead of the official API. */
    private final boolean useChinaMirror;

    public ModrinthDownloadProvider() {
        this(false);
    }

    public ModrinthDownloadProvider(boolean useChinaMirror) {
        this.useChinaMirror = useChinaMirror;
    }

    private String baseUrl() {
        return useChinaMirror ? CHINA_MIRROR : MODRINTH_API;
    }

    @Override
    public String getId() {
        return "modrinth";
    }

    @Override
    public boolean isChinaMirror() {
        return useChinaMirror;
    }

    @Override
    public int getPriority() {
        // Modrinth is an international source, lower priority than BMCLAPI
        return 45;
    }

    @Override
    public List<IDownloadProvider> getFallbackProviders() {
        // Fallback: try the other mirror variant (China ↔ International)
        return Collections.singletonList(
            new ModrinthDownloadProvider(!useChinaMirror)
        );
    }

    @Override
    public InstallerVersionList getForgeInstaller() {
        return null; // Modrinth installer list not yet implemented (Phase 3B UI pending)
    }

    @Override
    public InstallerVersionList getLiteLoaderInstaller() {
        // Modrinth does not host LiteLoader
        return null;
    }

    @Override
    public InstallerVersionList getOptiFineInstaller() {
        // Modrinth hosts OptiFine as a mod - accessible via generic version search
        return null;
    }

    @Override
    public String getLibraryDownloadURL() {
        // Modrinth does not mirror libraries
        return "https://libraries.minecraft.net";
    }

    @Override
    public String getVersionsDownloadURL() {
        return baseUrl() + "/minecraft/version";
    }

    @Override
    public String getIndexesDownloadURL() {
        // Not applicable to Modrinth
        return baseUrl() + "/minecraft/index";
    }

    @Override
    public String getVersionsListDownloadURL() {
        // Modrinth version manifest - Forge available versions
        return baseUrl() + "/minecraft/modloader/forge";
    }

    @Override
    public String getAssetsDownloadURL() {
        return "https://resources.download.minecraft.net";
    }

    @Override
    public String getParsedDownloadURL(String str) {
        if (str == null) return null;
        // Redirect Modrinth CDN URLs to China mirror
        // Ported from PCL: ModDownload.vb line ~1309-1315
        return str.replace("https://api.modrinth.com", baseUrl())
                  .replace("https://cdn.modrinth.com", useChinaMirror ? "https://mod.mcimirror.top" : "https://cdn.modrinth.com")
                  .replace("https://staging-api.modrinth.com", baseUrl());
    }

    @Override
    public boolean isAllowedToUseSelfURL() {
        return true;
    }
}
