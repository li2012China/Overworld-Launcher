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

/**
 * Curse download provider.
 * 
 * Phase 3B Enhancement (from PCL: ModDownload.vb DlSourceModGet()):
 * - Forge CDN: files.minecraftforge.net → ftb.cursecdn.com/FTB2/maven
 * - China mirror: mod.mcimirror.top/curseforge
 * - Also mirrors Modrinth CDN via mod.mcimirror.top
 */
public class CurseDownloadProvider extends MojangDownloadProvider {

    /** China mirror for Curse/Modrinth content - from PCL */
    private static final String CHINA_MIRROR = "https://mod.mcimirror.top";

    @Override
    public String getId() {
        return "curse";
    }

    @Override
    public boolean isChinaMirror() {
        return false; // Official source is international
    }

    @Override
    public int getPriority() {
        return 55; // Lower than BMCLAPI, higher than direct Mojang
    }

    @Override
    public List<IDownloadProvider> getFallbackProviders() {
        return Collections.singletonList(new BMCLAPIDownloadProvider());
    }

    @Override
    public String getParsedDownloadURL(String str) {
        if (str == null) return null;
        // Ported from PCL: ModDownload.vb line ~1309-1315
        return str
            // Forge Maven redirects
            .replace("http://files.minecraftforge.net/maven", "http://ftb.cursecdn.com/FTB2/maven")
            // China mirror for Curse content
            .replace("https://api.curseforge.com", CHINA_MIRROR + "/curseforge")
            .replace("https://edge.forgecdn.net", CHINA_MIRROR)
            .replace("https://mediafilez.forgecdn.net", CHINA_MIRROR)
            .replace("https://media.forgecdn.net", CHINA_MIRROR)
            // Modrinth CDN via China mirror
            .replace("https://cdn.modrinth.com", CHINA_MIRROR)
            .replace("https://api.modrinth.com", CHINA_MIRROR + "/modrinth");
    }

}
