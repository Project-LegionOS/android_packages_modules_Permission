/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.permission.ui.handheld;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.AppPermissionUsage;
import com.android.packageinstaller.permission.model.AppPermissions;
import com.android.packageinstaller.permission.utils.IconDrawableFactory;
import com.android.packageinstaller.permission.utils.Utils;
import com.android.permissioncontroller.R;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Show the usage of all permission groups by a single app.
 *
 * <p>Shows a list of app usage of permission groups, each of which links to
 * AppPermissionsFragment.
 */
public class AppPermissionUsageFragment extends SettingsWithButtonHeader {

    private static final String LOG_TAG = "AppPermissionUsageFragment";

    private @NonNull AppOpsManager mAppOpsManager;

    private @NonNull AppPermissions mAppPermissions;

    /**
     * @return A new fragment
     */
    public static @NonNull AppPermissionUsageFragment newInstance(@NonNull String packageName) {
        return setPackageName(new AppPermissionUsageFragment(), packageName);
    }

    private static <T extends Fragment> T setPackageName(T fragment, @NonNull String packageName) {
        Bundle arguments = new Bundle();
        arguments.putString(Intent.EXTRA_PACKAGE_NAME, packageName);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onStart() {
        super.onStart();
        getActivity().setTitle(R.string.app_permission_usage_title);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setLoading(true, false);
        setHasOptionsMenu(true);
        ActionBar ab = getActivity().getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
        }

        String packageName = getArguments().getString(Intent.EXTRA_PACKAGE_NAME);
        Activity activity = getActivity();
        PackageInfo packageInfo = getPackageInfo(activity, packageName);
        if (packageInfo == null) {
            Toast.makeText(activity, R.string.app_not_found_dlg_title, Toast.LENGTH_LONG).show();
            activity.finish();
            return;
        }
        mAppPermissions = new AppPermissions(activity, packageInfo, false,
                () -> getActivity().finish());
        mAppOpsManager = (AppOpsManager) getContext().getSystemService(AppOpsManager.class);
        addPreferences();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (mAppPermissions != null) {
            ApplicationInfo appInfo = mAppPermissions.getPackageInfo().applicationInfo;
            Drawable icon = IconDrawableFactory.getBadgedIcon(getActivity(), appInfo,
                    UserHandle.getUserHandleForUid(appInfo.uid));
            setHeader(icon, Utils.getFullAppLabel(appInfo, getContext()));
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getActivity().finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static PackageInfo getPackageInfo(@NonNull Activity activity,
            @NonNull String packageName) {
        try {
            return activity.getPackageManager().getPackageInfo(
                    packageName, PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            Log.i(LOG_TAG, "No package: " + activity.getCallingPackage(), e);
            return null;
        }
    }

    private void addPreferences() {
        Context context = getPreferenceManager().getContext();
        if (context == null) {
            return;
        }

        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            screen = getPreferenceManager().createPreferenceScreen(context);
            setPreferenceScreen(screen);
        }
        screen.removeAll();

        // Find the permission usages we want to add.
        List<AppPermissionUsage> usages = new ArrayList<>();
        Map<AppPermissionUsage, AppPermissionGroup> usageToGroup = new ArrayMap<>();
        Map<AppPermissionGroup, AppOpsManager.HistoricalPackageOps> groupToHistory =
                new ArrayMap<>();
        PackageInfo packageInfo = mAppPermissions.getPackageInfo();
        List<AppPermissionGroup> permissionGroups = mAppPermissions.getPermissionGroups();
        int numGroups = permissionGroups.size();
        for (int groupNum = 0; groupNum < numGroups; groupNum++) {
            AppPermissionGroup group = permissionGroups.get(groupNum);
            // Filter out third party permissions
            if (!group.getDeclaringPackage().equals(ManagePermissionsFragment.OS_PKG)) {
                continue;
            }
            // Ignore {READ,WRITE}_EXTERNAL_STORAGE since they're going away.
            if (group.getLabel().equals("Storage")) {
                continue;
            }
            if (!Utils.shouldShowPermission(context, group)) {
                continue;
            }
            List<AppPermissionUsage> groupUsages = group.getAppPermissionUsage();
            int numUsages = groupUsages.size();
            for (int usageNum = 0; usageNum < numUsages; usageNum++) {
                AppPermissionUsage usage = groupUsages.get(usageNum);
                if (usage.getTime() == 0) {
                    continue;
                }
                usages.add(usage);
                usageToGroup.put(usage, group);
            }

            groupToHistory.put(group,
                    Utils.getUsageForGroup(group, mAppOpsManager, 1000 * 60 * 60 * 24));
        }

        // Add the permission usages.
        usages.sort(Comparator.comparing(AppPermissionUsage::getTime).reversed());
        Set<String> addedEntries = new ArraySet<>();
        int numUsages = usages.size();
        for (int usageNum = 0; usageNum < numUsages; usageNum++) {
            AppPermissionUsage usage = usages.get(usageNum);
            // Filter out entries we've seen before.
            if (!addedEntries.add(usage.getPackageName() + "," + usage.getPermissionGroupName())) {
                continue;
            }

            AppPermissionGroup group = usageToGroup.get(usage);

            AppOpsManager.HistoricalPackageOps history = groupToHistory.get(group);
            long numAccesses = 0;
            long totalDuration = 0;
            for (int i = 0; i < history.getEntryCount(); i++) {
                AppOpsManager.HistoricalOpEntry historyEntry = history.getEntryAt(i);
                numAccesses += historyEntry.getForegroundAccessCount()
                        + historyEntry.getBackgroundAccessCount();
                totalDuration += historyEntry.getForegroundAccessDuration()
                        + historyEntry.getBackgroundAccessDuration();
            }

            Preference pref = new PermissionControlPreference(context, group);
            pref.setTitle(usage.getPermissionGroupLabel());
            long timeDiff = System.currentTimeMillis() - usage.getTime();
            if (totalDuration == 0) {
                pref.setSummary(
                        context.getString(R.string.app_permission_usage_summary_no_duration,
                                numAccesses, Utils.getTimeDiffStr(context, timeDiff)));
            } else {
                pref.setSummary(
                        context.getString(R.string.app_permission_usage_summary, numAccesses,
                                Utils.getTimeDiffStr(context, totalDuration),
                                Utils.getTimeDiffStr(context, timeDiff)));
            }
            pref.setIcon(Utils.applyTint(context, group.getIconResId(),
                    android.R.attr.colorControlNormal));
            pref.setKey(usage.getPackageName() + "," + usage.getPermissionGroupName());
            screen.addPreference(pref);
        }

        setLoading(false, true);
    }

}
