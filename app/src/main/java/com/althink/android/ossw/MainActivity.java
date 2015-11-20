package com.althink.android.ossw;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.NavigationView;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.althink.android.ossw.plugins.PluginsFragment;
import com.althink.android.ossw.service.OsswService;
import com.althink.android.ossw.service.ble.BleConnectionStatus;
import com.althink.android.ossw.watchsets.WatchSetsFragment;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private final static String TAG = MainActivity.class.getSimpleName();
    private static final String DRAWER_SELECTED_POSITION = "drawerSelectedItem";

    private OsswService mOsswBleService;

    /**
     * Fragment managing the behaviors, interactions and presentation of the navigation drawer.
     */
    private Toolbar mToolbar;
    private DrawerLayout mDrawer;
    private NavigationView navigationView;
    private ActionBarDrawerToggle drawerToggle;
    private int mPosition;

    private WatchSetsFragment mWatchsetsFragment;
    private PluginsFragment mPluginsFragment;
    private DeviceScanFragment mWatchesFragment;

//    static final int SELECT_WATCH_REQUEST = 1;

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mOsswBleService = ((OsswService.LocalBinder) service).getService();
            refreshConnectionAlert();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mOsswBleService = null;
        }
    };

    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            Log.i(TAG, "Intent received: " + intent);
            final String action = intent.getAction();
            if (OsswService.ACTION_WATCH_CONNECTING.equals(action)) {
                showConnectionAlertBar(R.string.connecting);
                invalidateOptionsMenu();
                Log.i(TAG, "Connecting to the watch");
            } else if (OsswService.ACTION_WATCH_CONNECTED.equals(action)) {
                Toast.makeText(MainActivity.this, getString(R.string.toast_watch_is_connected), Toast.LENGTH_SHORT).show();
//                hideConnectionAlertBar();
                showConnectionAlertBar(R.string.connected);
                invalidateOptionsMenu();
                Log.i(TAG, "Watch is connected");
            } else if (OsswService.ACTION_WATCH_DISCONNECTED.equals(action)) {
                showConnectionAlertBar(R.string.disconnected);
                invalidateOptionsMenu();
                Log.i(TAG, "Watch is disconnected");

            } else if (OsswService.ACTION_WATCH_AUTO_RECONNECT.equals(action)) {
                showConnectionAlertBar(R.string.auto_reconnect);
                invalidateOptionsMenu();
                Log.i(TAG, "Trying to auto reconnect");
            }
        }
    };

//    private void hideConnectionAlertBar() {
//        View view = findViewById(R.id.watch_connection_alert);
//        view.setVisibility(View.GONE);
//    }

    private void showConnectionAlertBar(int messageId) {
        TextView tv1 = (TextView) findViewById(R.id.connectionStatus);
        tv1.setText(messageId);
        View view = findViewById(R.id.watch_connection_alert);
        view.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //Log.i(TAG, "CREATE");

//        mHomeFragment = new HomeFragment();
        mPluginsFragment = new PluginsFragment();
        mWatchsetsFragment = new WatchSetsFragment();
        mWatchesFragment = new DeviceScanFragment();

        setContentView(R.layout.activity_main);
        mToolbar = (Toolbar) findViewById(R.id.toolbar_actionbar);
        setSupportActionBar(mToolbar);

        mDrawer = (DrawerLayout) findViewById(R.id.drawer);
        drawerToggle = new ActionBarDrawerToggle(this, mDrawer, mToolbar, R.string.drawer_open,  R.string.drawer_close);
        mDrawer.setDrawerListener(drawerToggle);

        navigationView = (NavigationView) findViewById(R.id.navigation_view);
        Menu menu = navigationView.getMenu();
        final List<MenuItem> items = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) {
            items.add(menu.getItem(i));
        }
        navigationView.setNavigationItemSelectedListener(new NavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(MenuItem menuItem) {
                onDrawerItemSelected(menuItem);
                mPosition = items.indexOf(menuItem);
                return true;
            }
        });

        FrameLayout frameLayout = (FrameLayout) findViewById(R.id.watch_connection_alert);
        frameLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPosition = 2;
                navigationView.getMenu().getItem(mPosition).setChecked(true);
                onDrawerItemSelected(navigationView.getMenu().getItem(mPosition));
            }
        });

        Intent osswServiceIntent = new Intent(this, OsswService.class);
        startService(osswServiceIntent);
        bindService(osswServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // The action bar home/up action should open or close the drawer.
        switch (item.getItemId()) {
            case android.R.id.home:
                mDrawer.openDrawer(GravityCompat.START);
                return true;
        }
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // Make sure this is the method with just `Bundle` as the signature
    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mPosition = savedInstanceState.getInt(DRAWER_SELECTED_POSITION);
        }
        onDrawerItemSelected(navigationView.getMenu().getItem(mPosition));
        drawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        drawerToggle.onConfigurationChanged(newConfig);
    }

    public void onDrawerItemSelected(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.nav_watchface:
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, mWatchsetsFragment).commit();
                break;
            case R.id.nav_extension:
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, mPluginsFragment).commit();
                break;
            case R.id.nav_watch:
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, mWatchesFragment).commit();
                break;
            case R.id.nav_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case R.id.nav_exit:
                mOsswBleService.disconnect();
                mOsswBleService.stopForeground(true);
                mOsswBleService.stopSelf();
                finish();
        }
        mDrawer.closeDrawers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        //Log.i(TAG, "RESUME");
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());

        refreshConnectionAlert();
    }

    private void refreshConnectionAlert() {
        if (mOsswBleService != null) {
            setConnectionAlertBar(mOsswBleService.getStatus());
        }
    }

    private void setConnectionAlertBar(BleConnectionStatus status) {
        if (status == BleConnectionStatus.CONNECTED) {
//            hideConnectionAlertBar();
            showConnectionAlertBar(R.string.connected);
        } else if (status == BleConnectionStatus.CONNECTING) {
            showConnectionAlertBar(R.string.connecting);
        } else if (status == BleConnectionStatus.AUTO_RECONNECT) {
            showConnectionAlertBar(R.string.auto_reconnect);
        } else {
            showConnectionAlertBar(R.string.disconnected);
        }
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(OsswService.ACTION_WATCH_CONNECTING);
        intentFilter.addAction(OsswService.ACTION_WATCH_CONNECTED);
        intentFilter.addAction(OsswService.ACTION_WATCH_DISCONNECTED);
        intentFilter.addAction(OsswService.ACTION_WATCH_AUTO_RECONNECT);
        return intentFilter;
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
        //Log.i(TAG, "PAUSE");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //Log.i(TAG, "DESTROY");
        unbindService(mServiceConnection);
//        unbindService(pluginServiceConnection);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(DRAWER_SELECTED_POSITION, mPosition);
    }

//    void connectToWatch(String address) {
//        mOsswBleService.connect(address);
//    }

    public Toolbar getToolbar() {
        return mToolbar;
    }

    @Override
    public void onBackPressed() {
        int count = getFragmentManager().getBackStackEntryCount();
        if (count == 0) {
            super.onBackPressed();
        } else {
            getFragmentManager().popBackStack();
        }
    }
}
