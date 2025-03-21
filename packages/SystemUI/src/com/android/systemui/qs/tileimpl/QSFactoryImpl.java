/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use mHost file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.tileimpl;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.view.ContextThemeWrapper;

import com.android.systemui.R;
import com.android.systemui.plugins.qs.*;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.tiles.AdbOverNetworkTile;
import com.android.systemui.qs.tiles.AirplaneModeTile;
import com.android.systemui.qs.tiles.AmbientDisplayTile;
import com.android.systemui.qs.tiles.BatterySaverTile;
import com.android.systemui.qs.tiles.BluetoothTile;
import com.android.systemui.qs.tiles.BootlegDumpsterTile;
import com.android.systemui.qs.tiles.CaffeineTile;
import com.android.systemui.qs.tiles.CastTile;
import com.android.systemui.qs.tiles.CellularTile;
import com.android.systemui.qs.tiles.CompassTile;
import com.android.systemui.qs.tiles.CPUInfoTile;
import com.android.systemui.qs.tiles.ColorInversionTile;
import com.android.systemui.qs.tiles.DataSaverTile;
import com.android.systemui.qs.tiles.DndTile;
import com.android.systemui.qs.tiles.ExpandedDesktopTile;
import com.android.systemui.qs.tiles.FlashlightTile;
import com.android.systemui.qs.tiles.GamingModeTile;
import com.android.systemui.qs.tiles.HeadsUpTile;
import com.android.systemui.qs.tiles.HotspotTile;
import com.android.systemui.qs.tiles.IntentTile;
import com.android.systemui.qs.tiles.LocationTile;
import com.android.systemui.qs.tiles.MusicTile;
import com.android.systemui.qs.tiles.NfcTile;
import com.android.systemui.qs.tiles.NightDisplayTile;
import com.android.systemui.qs.tiles.RebootTile;
import com.android.systemui.qs.tiles.RotationLockTile;
import com.android.systemui.qs.tiles.ScreenrecordTile;
import com.android.systemui.qs.tiles.SoundTile;
import com.android.systemui.qs.tiles.SoundSearchTile;
import com.android.systemui.qs.tiles.ScreenshotTile;
import com.android.systemui.qs.tiles.SmartPixelsTile;
import com.android.systemui.qs.tiles.SyncTile;
import com.android.systemui.qs.tiles.ThemeTile;
import com.android.systemui.qs.tiles.UserTile;
import com.android.systemui.qs.tiles.WifiTile;
import com.android.systemui.qs.tiles.WorkModeTile;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.util.leak.GarbageMonitor;

public class QSFactoryImpl implements QSFactory {

    private static final String TAG = "QSFactory";
    private final QSTileHost mHost;

    public QSFactoryImpl(QSTileHost host) {
        mHost = host;
    }

    public QSTile createTile(String tileSpec) {
        QSTileImpl tile = createTileInternal(tileSpec);
        if (tile != null) {
            tile.handleStale(); // Tile was just created, must be stale.
        }
        return tile;
    }

    private QSTileImpl createTileInternal(String tileSpec) {
        // Stock tiles.
        switch (tileSpec) {
            case "wifi":
                return new WifiTile(mHost);
            case "bt":
                return new BluetoothTile(mHost);
            case "cell":
                return new CellularTile(mHost);
            case "dnd":
                return new DndTile(mHost);
            case "inversion":
                return new ColorInversionTile(mHost);
            case "airplane":
                return new AirplaneModeTile(mHost);
            case "work":
                return new WorkModeTile(mHost);
            case "rotation":
                return new RotationLockTile(mHost);
            case "flashlight":
                return new FlashlightTile(mHost);
            case "location":
                return new LocationTile(mHost);
            case "cast":
                return new CastTile(mHost);
            case "hotspot":
                return new HotspotTile(mHost);
            case "user":
                return new UserTile(mHost);
            case "battery":
                return new BatterySaverTile(mHost);
            case "saver":
                return new DataSaverTile(mHost);
            case "night":
                return new NightDisplayTile(mHost);
            case "nfc":
                return new NfcTile(mHost);
            case "screenshot":
                return new ScreenshotTile(mHost);
            case "theme":
                return new ThemeTile(mHost);
            case "gaming":
                return new GamingModeTile(mHost);
            case "reboot":
                return new RebootTile(mHost);
            case "bootlegdump":
                return new BootlegDumpsterTile(mHost);
            case "music":
                return new MusicTile(mHost);
            case "soundsearch":
                return new SoundSearchTile(mHost);
            case "screenrecord":
                return new ScreenrecordTile(mHost);
            case "adb_network":
                return new AdbOverNetworkTile(mHost);
            case "expanded_desktop":
                return new ExpandedDesktopTile(mHost);
            case "sound":
                return new SoundTile(mHost);
            case "caffeine":
                return new CaffeineTile(mHost);
            case "compass":
                return new CompassTile(mHost);
            case "cpuinfo":
                return new CPUInfoTile(mHost);
            case "heads_up":
                return new HeadsUpTile(mHost);
            case "smartpixels":
                return new SmartPixelsTile(mHost);
            case "sync":
                return new SyncTile(mHost);
            case "ambient_display":
                return new AmbientDisplayTile(mHost);
        }
        // Intent tiles.
        if (tileSpec.startsWith(IntentTile.PREFIX)) return IntentTile.create(mHost, tileSpec);
        if (tileSpec.startsWith(CustomTile.PREFIX)) return CustomTile.create(mHost, tileSpec);

        // Debug tiles.
        if (Build.IS_DEBUGGABLE) {
            if (tileSpec.equals(GarbageMonitor.MemoryTile.TILE_SPEC)) {
                return new GarbageMonitor.MemoryTile(mHost);
            }
        }

        // Broken tiles.
        Log.w(TAG, "Bad tile spec: " + tileSpec);
        return null;
    }

    @Override
    public QSTileView createTileView(QSTile tile, boolean collapsedView) {
        Context context = new ContextThemeWrapper(mHost.getContext(), R.style.qs_theme);
        QSIconView icon = tile.createTileView(context);
        if (collapsedView) {
            return new QSTileBaseView(context, icon, collapsedView);
        } else {
            return new com.android.systemui.qs.tileimpl.QSTileView(context, icon);
        }
    }
}
