/* Copyright 2017 Andrew Dawson
 *
 * This file is a part of Tusky.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>. */

package com.keylesspalace.tusky;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.emoji.text.EmojiCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;
import androidx.viewpager2.widget.MarginPageTransformer;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.keylesspalace.tusky.appstore.CacheUpdater;
import com.keylesspalace.tusky.appstore.DrawerFooterClickedEvent;
import com.keylesspalace.tusky.appstore.EventHub;
import com.keylesspalace.tusky.appstore.MainTabsChangedEvent;
import com.keylesspalace.tusky.appstore.ProfileEditedEvent;
import com.keylesspalace.tusky.components.compose.ComposeActivity;
import com.keylesspalace.tusky.components.conversation.ConversationsRepository;
import com.keylesspalace.tusky.components.scheduled.ScheduledTootActivity;
import com.keylesspalace.tusky.components.search.SearchActivity;
import com.keylesspalace.tusky.db.AccountEntity;
import com.keylesspalace.tusky.entity.Account;
import com.keylesspalace.tusky.fragment.SFragment;
import com.keylesspalace.tusky.interfaces.ActionButtonActivity;
import com.keylesspalace.tusky.interfaces.ReselectableFragment;
import com.keylesspalace.tusky.pager.MainPagerAdapter;
import com.keylesspalace.tusky.util.CustomEmojiHelper;
import com.keylesspalace.tusky.util.NotificationHelper;
import com.keylesspalace.tusky.util.ShareShortcutHelper;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.materialdrawer.AccountHeader;
import com.mikepenz.materialdrawer.AccountHeaderBuilder;
import com.mikepenz.materialdrawer.Drawer;
import com.mikepenz.materialdrawer.DrawerBuilder;
import com.mikepenz.materialdrawer.model.DividerDrawerItem;
import com.mikepenz.materialdrawer.model.PrimaryDrawerItem;
import com.mikepenz.materialdrawer.model.ProfileDrawerItem;
import com.mikepenz.materialdrawer.model.ProfileSettingDrawerItem;
import com.mikepenz.materialdrawer.model.SecondaryDrawerItem;
import com.mikepenz.materialdrawer.model.interfaces.IDrawerItem;
import com.mikepenz.materialdrawer.model.interfaces.IProfile;
import com.mikepenz.materialdrawer.util.AbstractDrawerImageLoader;
import com.mikepenz.materialdrawer.util.DrawerImageLoader;

import net.accelf.yuito.QuickTootHelper;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.android.AndroidInjector;
import dagger.android.DispatchingAndroidInjector;
import dagger.android.HasAndroidInjector;
import io.reactivex.android.schedulers.AndroidSchedulers;

import static com.keylesspalace.tusky.util.MediaUtilsKt.deleteStaleCachedMedia;
import static com.uber.autodispose.AutoDispose.autoDisposable;
import static com.uber.autodispose.android.lifecycle.AndroidLifecycleScopeProvider.from;

public final class MainActivity extends BottomSheetActivity implements ActionButtonActivity,
        HasAndroidInjector {

    private static final String TAG = "MainActivity"; // logging tag
    private static final long DRAWER_ITEM_ADD_ACCOUNT = -13;
    private static final long DRAWER_ITEM_EDIT_PROFILE = 0;
    private static final long DRAWER_ITEM_FAVOURITES = 1;
    private static final long DRAWER_ITEM_BOOKMARKS = 2;
    private static final long DRAWER_ITEM_LISTS = 3;
    private static final long DRAWER_ITEM_SEARCH = 4;
    private static final long DRAWER_ITEM_SAVED_TOOT = 5;
    private static final long DRAWER_ITEM_ACCOUNT_SETTINGS = 6;
    private static final long DRAWER_ITEM_SETTINGS = 7;
    private static final long DRAWER_ITEM_ABOUT = 8;
    private static final long DRAWER_ITEM_LOG_OUT = 9;
    private static final long DRAWER_ITEM_FOLLOW_REQUESTS = 10;
    private static final long DRAWER_ITEM_SCHEDULED_TOOT = 11;
    public static final String STATUS_URL = "statusUrl";

    @Inject
    public DispatchingAndroidInjector<Object> androidInjector;
    @Inject
    public EventHub eventHub;
    @Inject
    public CacheUpdater cacheUpdater;
    @Inject
    ConversationsRepository conversationRepository;

    private FloatingActionButton composeButton;
    private AccountHeader headerResult;
    private Drawer drawer;
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private SharedPreferences defPrefs;

    private int notificationTabPosition;
    private MainPagerAdapter adapter;

    private final EmojiCompat.InitCallback emojiInitCallback = new EmojiCompat.InitCallback() {
        @Override
        public void onInitialized() {
            if(!isDestroyed()) {
                updateProfiles();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (accountManager.getActiveAccount() == null) {
            // will be redirected to LoginActivity by BaseActivity
            return;
        }

        Intent intent = getIntent();
        boolean showNotificationTab = false;

        if (intent != null) {

            /** there are two possibilities the accountId can be passed to MainActivity:
             - from our code as long 'account_id'
             - from share shortcuts as String 'android.intent.extra.shortcut.ID'
             */
            long accountId = intent.getLongExtra(NotificationHelper.ACCOUNT_ID, -1);
            if(accountId == -1) {
                String accountIdString = intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID);
                if(accountIdString != null) {
                    accountId = Long.parseLong(accountIdString);
                }
            }
            boolean accountRequested = (accountId != -1);

            if (accountRequested) {
                AccountEntity account = accountManager.getActiveAccount();
                if (account == null || accountId != account.getId()) {
                    accountManager.setActiveAccount(accountId);
                }
            }

            if (ComposeActivity.canHandleMimeType(intent.getType())) {
                // Sharing to Tusky from an external app
                if (accountRequested) {
                    // The correct account is already active
                    forwardShare(intent);
                } else {
                    // No account was provided, show the chooser
                    showAccountChooserDialog(getString(R.string.action_share_as), true, account -> {
                        long requestedId = account.getId();
                        AccountEntity activeAccount = accountManager.getActiveAccount();
                        if (activeAccount != null && requestedId == activeAccount.getId()) {
                            // The correct account is already active
                            forwardShare(intent);
                        } else {
                            // A different account was requested, restart the activity
                            intent.putExtra(NotificationHelper.ACCOUNT_ID, requestedId);
                            changeAccount(requestedId, intent);
                        }
                    });
                }
            } else if (accountRequested) {
                // user clicked a notification, show notification tab and switch user if necessary
                showNotificationTab = true;
            }
        }
        setContentView(R.layout.activity_main);

        composeButton = findViewById(R.id.floating_btn);
        tabLayout = findViewById(R.id.tab_layout);
        viewPager = findViewById(R.id.pager);

        ConstraintLayout quickTootContainer = findViewById(R.id.quick_toot_container);
        QuickTootHelper quickTootHelper = new QuickTootHelper(this, quickTootContainer, accountManager, eventHub);

        composeButton.setOnClickListener(v -> quickTootHelper.composeButton());
        tabLayout.requestFocus();

        setupDrawer();

        /* Fetch user info while we're doing other things. This has to be done after setting up the
         * drawer, though, because its callback touches the header in the drawer. */
        fetchUserInfo();

        setupTabs(showNotificationTab);

        defPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        int pageMargin = getResources().getDimensionPixelSize(R.dimen.tab_page_margin);
        viewPager.setPageTransformer(new MarginPageTransformer(pageMargin));
        if (defPrefs.getBoolean("viewPagerOffScreenLimit", false)) {
            viewPager.setOffscreenPageLimit(9);
        }

        PopupWindow popupWindow = new PopupWindow(this);
        @SuppressLint("InflateParams")
        View popupView = getLayoutInflater().inflate(R.layout.view_tab_action, null);
        popupWindow.setContentView(popupView);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                viewPager.setCurrentItem(tab.getPosition());

                if (tab.getPosition() == notificationTabPosition) {
                    NotificationHelper.clearNotificationsForActiveAccount(MainActivity.this, accountManager);
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                popupView.findViewById(R.id.tab_jump_to_top)
                        .setOnClickListener(v -> {
                            if (adapter != null) {
                                Fragment fragment = adapter.getFragment(tab.getPosition());
                                if (fragment instanceof ReselectableFragment) {
                                    ((ReselectableFragment) fragment).onReselect();
                                }
                            }

                            popupWindow.dismiss();
                        });
                popupView.findViewById(R.id.tab_reset)
                        .setOnClickListener(v -> {
                            if (adapter != null) {
                                Fragment fragment = adapter.getFragment(tab.getPosition());
                                if (fragment instanceof ReselectableFragment) {
                                    ((ReselectableFragment) fragment).onReset();
                                }
                            }

                            popupWindow.dismiss();
                        });
                popupWindow.showAsDropDown(tab.view);
            }
        });

        // Setup push notifications
        if (NotificationHelper.areNotificationsEnabled(this, accountManager)) {
            NotificationHelper.enablePullNotifications();
        } else {
            NotificationHelper.disablePullNotifications();
        }

        eventHub.getEvents()
                .observeOn(AndroidSchedulers.mainThread())
                .as(autoDisposable(from(this, Lifecycle.Event.ON_DESTROY)))
                .subscribe(event -> {
                    if (event instanceof ProfileEditedEvent) {
                        onFetchUserInfoSuccess(((ProfileEditedEvent) event).getNewProfileData());
                    }
                    if (event instanceof MainTabsChangedEvent) {
                        setupTabs(false);
                    }
                    quickTootHelper.handleEvent(event);
                });

        // Flush old media that was cached for sharing
        deleteStaleCachedMedia(getApplicationContext().getExternalFilesDir("Tusky"));
    }

    @Override
    protected void onResume() {
        super.onResume();

        NotificationHelper.clearNotificationsForActiveAccount(this, accountManager);

    }

    @Override
    protected void onStart() {
        super.onStart();
        startStreaming();
    }

    private void startStreaming() {
        if (defPrefs.getBoolean("useHTLStream", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopStreaming();
    }

    private void stopStreaming() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onBackPressed() {
        if (drawer != null && drawer.isDrawerOpen()) {
            drawer.closeDrawer();
        } else if (viewPager.getCurrentItem() != 0) {
            viewPager.setCurrentItem(0);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU: {
                if (drawer.isDrawerOpen()) {
                    drawer.closeDrawer();
                } else {
                    drawer.openDrawer();
                }
                return true;
            }
            case KeyEvent.KEYCODE_SEARCH: {
                startActivityWithSlideInAnimation(SearchActivity.getIntent(this));
                return true;
            }
        }

        if (event.isCtrlPressed() || event.isShiftPressed()) {
            // FIXME: blackberry keyONE raises SHIFT key event even CTRL IS PRESSED
            switch (keyCode) {
                case KeyEvent.KEYCODE_N: {
                    // open compose activity by pressing SHIFT + N (or CTRL + N)
                    Intent composeIntent = new Intent(getApplicationContext(), ComposeActivity.class);
                    startActivity(composeIntent);
                    return true;
                }
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent != null) {
            String statusUrl = intent.getStringExtra(STATUS_URL);
            if (statusUrl != null) {
                viewUrl(statusUrl, statusUrl, PostLookupFallbackBehavior.DISPLAY_ERROR);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EmojiCompat.get().unregisterInitCallback(emojiInitCallback);
    }

    private void forwardShare(Intent intent) {
        Intent composeIntent = new Intent(this, ComposeActivity.class);
        composeIntent.setAction(intent.getAction());
        composeIntent.setType(intent.getType());
        composeIntent.putExtras(intent);
        composeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(composeIntent);
        finish();
    }

    private void setupDrawer() {
        headerResult = new AccountHeaderBuilder()
                .withActivity(this)
                .withDividerBelowHeader(false)
                .withHeaderBackgroundScaleType(ImageView.ScaleType.CENTER_CROP)
                .withCurrentProfileHiddenInList(true)
                .withOnAccountHeaderListener((view, profile, current) -> handleProfileClick(profile, current))
                .addProfiles(
                        new ProfileSettingDrawerItem()
                                .withIdentifier(DRAWER_ITEM_ADD_ACCOUNT)
                                .withName(R.string.add_account_name)
                                .withDescription(R.string.add_account_description)
                                .withIcon(GoogleMaterial.Icon.gmd_add))
                .build();

        headerResult.getView()
                .findViewById(R.id.material_drawer_account_header_current)
                .setContentDescription(getString(R.string.action_view_profile));

        ImageView background = headerResult.getHeaderBackgroundView();
        background.setColorFilter(ContextCompat.getColor(this, R.color.header_background_filter));
        background.setBackgroundColor(ContextCompat.getColor(this, R.color.tusky_grey_10));

        final boolean animateAvatars = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("animateGifAvatars", false);

        DrawerImageLoader.init(new AbstractDrawerImageLoader() {
            @Override
            public void set(ImageView imageView, Uri uri, Drawable placeholder, String tag) {
                if(animateAvatars) {
                    Glide.with(MainActivity.this)
                            .load(uri)
                            .placeholder(placeholder)
                            .into(imageView);
                } else {
                    Glide.with(MainActivity.this)
                            .asBitmap()
                            .load(uri)
                            .placeholder(placeholder)
                            .into(imageView);
                }

            }

            @Override
            public void cancel(ImageView imageView) {
                Glide.with(MainActivity.this).clear(imageView);
            }
        });

        List<IDrawerItem> listItems = new ArrayList<>(11);
        listItems.add(new PrimaryDrawerItem().withIdentifier(DRAWER_ITEM_EDIT_PROFILE).withName(R.string.action_edit_profile).withSelectable(false).withIcon(GoogleMaterial.Icon.gmd_person));
        listItems.add(new PrimaryDrawerItem().withIdentifier(DRAWER_ITEM_FAVOURITES).withName(R.string.action_view_favourites).withSelectable(false).withIcon(GoogleMaterial.Icon.gmd_star));
        listItems.add(new PrimaryDrawerItem().withIdentifier(DRAWER_ITEM_BOOKMARKS).withName(R.string.action_view_bookmarks).withSelectable(false).withIcon(GoogleMaterial.Icon.gmd_bookmark));
        listItems.add(new PrimaryDrawerItem().withIdentifier(DRAWER_ITEM_LISTS).withName(R.string.action_lists).withSelectable(false).withIcon(GoogleMaterial.Icon.gmd_list));
        listItems.add(new PrimaryDrawerItem().withIdentifier(DRAWER_ITEM_SEARCH).withName(R.string.action_search).withSelectable(false).withIcon(GoogleMaterial.Icon.gmd_search));
        listItems.add(new PrimaryDrawerItem().withIdentifier(DRAWER_ITEM_SAVED_TOOT).withName(R.string.action_access_saved_toot).withSelectable(false).withIcon(R.drawable.ic_notebook).withIconTintingEnabled(true));
        listItems.add(new PrimaryDrawerItem().withIdentifier(DRAWER_ITEM_SCHEDULED_TOOT).withName(R.string.action_access_scheduled_toot).withSelectable(false).withIcon(R.drawable.ic_access_time).withIconTintingEnabled(true));
        listItems.add(new DividerDrawerItem());
        listItems.add(new SecondaryDrawerItem().withIdentifier(DRAWER_ITEM_ACCOUNT_SETTINGS).withName(R.string.action_view_account_preferences).withSelectable(false).withIcon(R.drawable.ic_account_settings).withIconTintingEnabled(true));
        listItems.add(new SecondaryDrawerItem().withIdentifier(DRAWER_ITEM_SETTINGS).withName(R.string.action_view_preferences).withSelectable(false).withIcon(GoogleMaterial.Icon.gmd_settings));
        listItems.add(new SecondaryDrawerItem().withIdentifier(DRAWER_ITEM_ABOUT).withName(R.string.about_title_activity).withSelectable(false).withIcon(GoogleMaterial.Icon.gmd_info));
        listItems.add(new SecondaryDrawerItem().withIdentifier(DRAWER_ITEM_LOG_OUT).withName(R.string.action_logout).withSelectable(false).withIcon(R.drawable.ic_logout).withIconTintingEnabled(true));

        drawer = new DrawerBuilder()
                .withActivity(this)
                .withAccountHeader(headerResult)
                .withHasStableIds(true)
                .withSelectedItem(-1)
                .withDrawerItems(listItems)
                .withToolbar(findViewById(R.id.main_toolbar))
                .withOnDrawerItemClickListener((view, position, drawerItem) -> {
                    if (drawerItem != null) {
                        long drawerItemIdentifier = drawerItem.getIdentifier();

                        if (drawerItemIdentifier == DRAWER_ITEM_EDIT_PROFILE) {
                            Intent intent = new Intent(MainActivity.this, EditProfileActivity.class);
                            startActivityWithSlideInAnimation(intent);
                        } else if (drawerItemIdentifier == DRAWER_ITEM_FAVOURITES) {
                            Intent intent = StatusListActivity.newFavouritesIntent(MainActivity.this);
                            startActivityWithSlideInAnimation(intent);
                        } else if (drawerItemIdentifier == DRAWER_ITEM_BOOKMARKS) {
                            Intent intent = StatusListActivity.newBookmarksIntent(MainActivity.this);
                            startActivityWithSlideInAnimation(intent);
                        } else if (drawerItemIdentifier == DRAWER_ITEM_SEARCH) {
                            startActivityWithSlideInAnimation(SearchActivity.getIntent(this));
                        } else if (drawerItemIdentifier == DRAWER_ITEM_ACCOUNT_SETTINGS) {
                            Intent intent = PreferencesActivity.newIntent(MainActivity.this, PreferencesActivity.ACCOUNT_PREFERENCES);
                            startActivityWithSlideInAnimation(intent);
                        } else if (drawerItemIdentifier == DRAWER_ITEM_SETTINGS) {
                            Intent intent = PreferencesActivity.newIntent(MainActivity.this, PreferencesActivity.GENERAL_PREFERENCES);
                            startActivityWithSlideInAnimation(intent);
                        } else if (drawerItemIdentifier == DRAWER_ITEM_ABOUT) {
                            Intent intent = new Intent(MainActivity.this, AboutActivity.class);
                            startActivityWithSlideInAnimation(intent);
                        } else if (drawerItemIdentifier == DRAWER_ITEM_LOG_OUT) {
                            logout();
                        } else if (drawerItemIdentifier == DRAWER_ITEM_FOLLOW_REQUESTS) {
                            Intent intent = new Intent(MainActivity.this, AccountListActivity.class);
                            intent.putExtra("type", AccountListActivity.Type.FOLLOW_REQUESTS);
                            startActivityWithSlideInAnimation(intent);
                        } else if (drawerItemIdentifier == DRAWER_ITEM_SAVED_TOOT) {
                            Intent intent = new Intent(MainActivity.this, SavedTootActivity.class);
                            startActivityWithSlideInAnimation(intent);
                        } else if (drawerItemIdentifier == DRAWER_ITEM_SCHEDULED_TOOT) {
                            startActivityWithSlideInAnimation(ScheduledTootActivity.newIntent(this));
                        } else if (drawerItemIdentifier == DRAWER_ITEM_LISTS) {
                            startActivityWithSlideInAnimation(ListsActivity.newIntent(this));
                        }

                    }

                    return false;
                })
                .withStickyFooter(R.layout.drawer_footer)
                .build();

        setupDrawerFooter(drawer.getStickyFooter());

        if (BuildConfig.DEBUG) {
            IDrawerItem debugItem = new SecondaryDrawerItem()
                    .withIdentifier(1337)
                    .withName("debug")
                    .withDisabledTextColor(Color.GREEN)
                    .withSelectable(false)
                    .withEnabled(false);
            drawer.addItem(debugItem);
        }

        IDrawerItem exTextItem = new SecondaryDrawerItem()
                .withIdentifier(219)
                .withName("Yuito (by kyori19)")
                .withDisabledTextColor(Color.YELLOW)
                .withSelectable(false)
                .withEnabled(false);
        drawer.addItem(exTextItem);

        EmojiCompat.get().registerInitCallback(emojiInitCallback);
    }

    private void setupDrawerFooter(View view) {
        TextView instanceData = view.findViewById(R.id.instance_data);
        instanceData.setTextColor(instanceData.getHintTextColors());

        mastodonApi.getInstance()
                .observeOn(AndroidSchedulers.mainThread())
                .as(autoDisposable(from(this, Lifecycle.Event.ON_DESTROY)))
                .subscribe(
                        instance -> instanceData.setText(String.format("%s\n%s\n%s", instance.getTitle(), instance.getUri(), instance.getVersion())),
                        throwable -> instanceData.setText(getString(R.string.instance_data_failed))
                );

        instanceData.setOnClickListener(v -> {
            String infoText = ((TextView) v).getText().toString();
            infoText += "?";
            if (infoText.endsWith("???????")) {
                infoText = infoText.substring(0, infoText.length() - 7);
                eventHub.dispatch(new DrawerFooterClickedEvent(true));
                drawer.closeDrawer();
            }
            ((TextView) v).setText(infoText);
        });

        view.setPadding(0, 0, 0, 0);
    }

    private void setupTabs(boolean selectNotificationTab) {
        List<TabData> tabs = accountManager.getActiveAccount().getTabPreferences();

        adapter = new MainPagerAdapter(tabs, this);
        viewPager.setAdapter(adapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> { }).attach();

        tabLayout.removeAllTabs();
        for (int i = 0; i < tabs.size(); i++) {
            TabLayout.Tab tab = tabLayout.newTab()
                    .setIcon(tabs.get(i).getIcon());
            if (tabs.get(i).getId().equals(TabDataKt.LIST)) {
                tab.setContentDescription(tabs.get(i).getArguments().get(1));
            } else {
                tab.setContentDescription(tabs.get(i).getText());
            }
            tabLayout.addTab(tab);
            if (tabs.get(i).getId().equals(TabDataKt.NOTIFICATIONS)) {
                notificationTabPosition = i;
                if (selectNotificationTab) {
                    tab.select();
                }
            }
        }

    }

    private boolean handleProfileClick(IProfile profile, boolean current) {
        AccountEntity activeAccount = accountManager.getActiveAccount();

        //open profile when active image was clicked
        if (current && activeAccount != null) {
            Intent intent = AccountActivity.getIntent(this, activeAccount.getAccountId());
            startActivityWithSlideInAnimation(intent);
            new Handler().postDelayed(() -> drawer.closeDrawer(), 100);
            return true;
        }
        //open LoginActivity to add new account
        if (profile.getIdentifier() == DRAWER_ITEM_ADD_ACCOUNT) {
            startActivityWithSlideInAnimation(LoginActivity.getIntent(this, true));
            new Handler().postDelayed(() -> drawer.closeDrawer(), 100);
            return true;
        }
        //change Account
        changeAccount(profile.getIdentifier(), null);
        return false;
    }


    private void changeAccount(long newSelectedId, @Nullable Intent forward) {
        cacheUpdater.stop();
        SFragment.flushFilters();
        accountManager.setActiveAccount(newSelectedId);

        stopStreaming();

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (forward != null) {
            intent.setType(forward.getType());
            intent.setAction(forward.getAction());
            intent.putExtras(forward);
        }
        startActivity(intent);
        finishWithoutSlideOutAnimation();

        overridePendingTransition(R.anim.explode, R.anim.explode);
    }

    private void logout() {

        AccountEntity activeAccount = accountManager.getActiveAccount();

        if (activeAccount != null) {

            new AlertDialog.Builder(this)
                    .setTitle(R.string.action_logout)
                    .setMessage(getString(R.string.action_logout_confirm, activeAccount.getFullName()))
                    .setPositiveButton(android.R.string.yes, (dialog, which) -> {

                        NotificationHelper.deleteNotificationChannelsForAccount(accountManager.getActiveAccount(), MainActivity.this);
                        cacheUpdater.clearForUser(activeAccount.getId());
                        conversationRepository.deleteCacheForAccount(activeAccount.getId());
                        ShareShortcutHelper.removeShortcut(this, activeAccount);

                        AccountEntity newAccount = accountManager.logActiveAccountOut();

                        if (!NotificationHelper.areNotificationsEnabled(MainActivity.this, accountManager)) {
                            NotificationHelper.disablePullNotifications();
                        }

                        Intent intent;
                        if (newAccount == null) {
                            intent = LoginActivity.getIntent(MainActivity.this, false);
                        } else {
                            intent = new Intent(MainActivity.this, MainActivity.class);
                        }
                        startActivity(intent);
                        finishWithoutSlideOutAnimation();
                    })
                    .setNegativeButton(android.R.string.no, null)
                    .show();
        }
    }

    private void fetchUserInfo() {
        mastodonApi.accountVerifyCredentials()
                .observeOn(AndroidSchedulers.mainThread())
                .as(autoDisposable(from(this, Lifecycle.Event.ON_DESTROY)))
                .subscribe(this::onFetchUserInfoSuccess, MainActivity::onFetchUserInfoFailure);
    }

    private void onFetchUserInfoSuccess(Account me) {

        // Add the header image and avatar from the account, into the navigation drawer header.

        ImageView background = headerResult.getHeaderBackgroundView();

        Glide.with(MainActivity.this)
                .asBitmap()
                .load(me.getHeader())
                .into(background);

        accountManager.updateActiveAccount(me);

        NotificationHelper.createNotificationChannelsForAccount(accountManager.getActiveAccount(), this);

        // Show follow requests in the menu, if this is a locked account.
        if (me.getLocked() && drawer.getDrawerItem(DRAWER_ITEM_FOLLOW_REQUESTS) == null) {
            PrimaryDrawerItem followRequestsItem = new PrimaryDrawerItem()
                    .withIdentifier(DRAWER_ITEM_FOLLOW_REQUESTS)
                    .withName(R.string.action_view_follow_requests)
                    .withSelectable(false)
                    .withIcon(GoogleMaterial.Icon.gmd_person_add);
            drawer.addItemAtPosition(followRequestsItem, 4);
        } else if (!me.getLocked()) {
            drawer.removeItem(DRAWER_ITEM_FOLLOW_REQUESTS);
        }

        updateProfiles();

        ShareShortcutHelper.updateShortcut(this, accountManager.getActiveAccount());

    }

    private void updateProfiles() {

        List<AccountEntity> allAccounts = accountManager.getAllAccountsOrderedByActive();

        List<IProfile> profiles = new ArrayList<>(allAccounts.size() + 1);

        for (AccountEntity acc : allAccounts) {
            CharSequence emojifiedName = CustomEmojiHelper.emojifyString(acc.getDisplayName(), acc.getEmojis(), headerResult.getView());
            emojifiedName = EmojiCompat.get().process(emojifiedName);

            profiles.add(
                    new ProfileDrawerItem()
                            .withSetSelected(acc.isActive())
                            .withName(emojifiedName)
                            .withIcon(acc.getProfilePictureUrl())
                            .withNameShown(true)
                            .withIdentifier(acc.getId())
                            .withEmail(acc.getFullName()));

        }

        // reuse the already existing "add account" item
        for (IProfile profile : headerResult.getProfiles()) {
            if (profile.getIdentifier() == DRAWER_ITEM_ADD_ACCOUNT) {
                profiles.add(profile);
                break;
            }
        }
        headerResult.clear();
        headerResult.setProfiles(profiles);
        headerResult.setActiveProfile(accountManager.getActiveAccount().getId());
    }

    private static void onFetchUserInfoFailure(Throwable throwable) {
        Log.e(TAG, "Failed to fetch user info. " + throwable.getMessage());
    }

    @Nullable
    @Override
    public FloatingActionButton getActionButton() {
        return composeButton;
    }

    @Override
    public AndroidInjector<Object> androidInjector() {
        return androidInjector;
    }
}