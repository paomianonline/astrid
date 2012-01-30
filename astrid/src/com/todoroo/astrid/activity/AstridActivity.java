package com.todoroo.astrid.activity;

import android.app.Dialog;
import android.app.PendingIntent.CanceledException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.view.View;

import com.timsu.astrid.R;
import com.todoroo.andlib.utility.AndroidUtilities;
import com.todoroo.astrid.api.Filter;
import com.todoroo.astrid.api.FilterListItem;
import com.todoroo.astrid.api.FilterWithCustomIntent;
import com.todoroo.astrid.api.IntentFilter;
import com.todoroo.astrid.core.SearchFilter;
import com.todoroo.astrid.reminders.NotificationFragment;
import com.todoroo.astrid.reminders.Notifications;
import com.todoroo.astrid.reminders.ReminderDialog;
import com.todoroo.astrid.service.StartupService;
import com.todoroo.astrid.service.StatisticsConstants;
import com.todoroo.astrid.service.StatisticsService;

/**
 * This wrapper activity contains all the glue-code to handle the callbacks between the different
 * fragments that could be visible on the screen in landscape-mode.
 * So, it basically contains all the callback-code from the filterlist-fragment, the tasklist-fragments
 * and the taskedit-fragment (and possibly others that should be handled).
 * Using this AstridWrapperActivity helps to avoid duplicated code because its all gathered here for sub-wrapperactivities
 * to use.
 *
 * @author Arne
 *
 */
public class AstridActivity extends FragmentActivity
    implements FilterListFragment.OnFilterItemClickedListener,
    TaskListFragment.OnTaskListItemClickedListener,
    TaskEditFragment.OnTaskEditDetailsClickedListener {

    public static final int LAYOUT_SINGLE = 0;
    public static final int LAYOUT_DOUBLE = 1;
    public static final int LAYOUT_TRIPLE = 2;

    protected int fragmentLayout = LAYOUT_SINGLE;

    private final ReminderReceiver reminderReceiver = new ReminderReceiver();

    public FilterListFragment getFilterListFragment() {
        FilterListFragment frag = (FilterListFragment) getSupportFragmentManager()
                .findFragmentByTag(FilterListFragment.TAG_FILTERLIST_FRAGMENT);

        return frag;
    }

    public TaskListFragment getTaskListFragment() {
        TaskListFragment frag = (TaskListFragment) getSupportFragmentManager()
                .findFragmentByTag(TaskListFragment.TAG_TASKLIST_FRAGMENT);

        return frag;
    }

    public TaskEditFragment getTaskEditFragment() {
        TaskEditFragment frag = (TaskEditFragment) getSupportFragmentManager()
                .findFragmentByTag(TaskEditFragment.TAG_TASKEDIT_FRAGMENT);

        return frag;
    }



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new StartupService().onStartupApplication(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        FilterListFragment frag = getFilterListFragment();
        if (frag != null) {
            frag.onNewIntent(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.content.IntentFilter intentFilter = new android.content.IntentFilter(Notifications.BROADCAST_IN_APP_NOTIFY);
        intentFilter.setPriority(1);
        registerReceiver(reminderReceiver, intentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(reminderReceiver);
    }

    /**
     * Handles items being clicked from the filterlist-fragment. Return true if item is handled.
     */
    public boolean onFilterItemClicked(FilterListItem item) {
        if (this instanceof TaskListActivity && (item instanceof Filter) ) {
            ((TaskListActivity) this).setSelectedItem((Filter) item);
        }
        if (item instanceof SearchFilter) {
            onSearchRequested();
            StatisticsService.reportEvent(StatisticsConstants.FILTER_SEARCH);
            return false;
        } else {
            // If showing both fragments, directly update the tasklist-fragment
            Intent intent = getIntent();

            if(item instanceof Filter) {
                Filter filter = (Filter)item;
                if(filter instanceof FilterWithCustomIntent) {
                    int lastSelectedList = intent.getIntExtra(FilterListFragment.TOKEN_LAST_SELECTED, 0);
                    intent = ((FilterWithCustomIntent)filter).getCustomIntent();
                    intent.putExtra(FilterListFragment.TOKEN_LAST_SELECTED, lastSelectedList);
                } else {
                    intent.putExtra(TaskListFragment.TOKEN_FILTER, filter);
                }

                setIntent(intent);

                setupTasklistFragmentWithFilter(filter);

                // no animation for dualpane-layout
                AndroidUtilities.callOverridePendingTransition(this, 0, 0);
                StatisticsService.reportEvent(StatisticsConstants.FILTER_LIST);
                return true;
            } else if(item instanceof IntentFilter) {
                try {
                    ((IntentFilter)item).intent.send();
                } catch (CanceledException e) {
                    // ignore
                }
            }
            return false;
        }
    }

    protected final void setupTasklistFragmentWithFilter(Filter filter) {
        setupTasklistFragmentWithFilterAndCustomTaskList(filter, TaskListFragment.class);
    }

    protected final void setupTasklistFragmentWithFilterAndCustomTaskList(Filter filter, Class<?> customTaskList) {
        Class<?> component = customTaskList;
        if (filter instanceof FilterWithCustomIntent) {
            try {
                component = Class.forName(((FilterWithCustomIntent) filter).customTaskList.getClassName());
            } catch (Exception e) {
                // Invalid
            }
        }
        FragmentManager manager = getSupportFragmentManager();
        FragmentTransaction transaction = manager.beginTransaction();

        try {
            TaskListFragment newFragment = (TaskListFragment) component.newInstance();
            transaction.replace(R.id.tasklist_fragment_container, newFragment,
                    TaskListFragment.TAG_TASKLIST_FRAGMENT);
            transaction.commit();
        } catch (Exception e) {
            // Don't worry about it
        }
    }

    @Override
    public void onTaskListItemClicked(long taskId) {
        Intent intent = new Intent(this, TaskEditActivity.class);
        intent.putExtra(TaskEditFragment.TOKEN_ID, taskId);
        getIntent().putExtra(TaskEditFragment.TOKEN_ID, taskId); // Needs to be in activity intent so that TEA onResume doesn't create a blank activity
        if (getIntent().hasExtra(TaskListFragment.TOKEN_FILTER))
            intent.putExtra(TaskListFragment.TOKEN_FILTER, getIntent().getParcelableExtra(TaskListFragment.TOKEN_FILTER));

        if (fragmentLayout != LAYOUT_SINGLE) {
            TaskEditFragment editActivity = getTaskEditFragment();
            if (fragmentLayout == LAYOUT_TRIPLE)
                findViewById(R.id.taskedit_fragment_container).setVisibility(View.VISIBLE);

            if(editActivity == null) {
                editActivity = new TaskEditFragment();
                FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
                transaction.add(R.id.taskedit_fragment_container, editActivity, TaskEditFragment.TAG_TASKEDIT_FRAGMENT);
                transaction.addToBackStack(null);
                transaction.commit();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Force the transaction to occur so that we can be guaranteed of the fragment existing if we try to present it
                        getSupportFragmentManager().executePendingTransactions();
                    }
                });
            }

            editActivity.save(true);
            editActivity.repopulateFromScratch(intent);

        } else {
            startActivityForResult(intent, TaskListFragment.ACTIVITY_EDIT_TASK);
            AndroidUtilities.callOverridePendingTransition(this, R.anim.slide_left_in, R.anim.slide_left_out);
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    public void onTaskEditDetailsClicked(int category, int position) {
        //

    }

    // --- fragment helpers

    protected void removeFragment(String tag) {
        FragmentManager fm = getSupportFragmentManager();
        Fragment fragment = fm.findFragmentByTag(tag);
        if(fragment != null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.remove(fragment);
            ft.commit();
        }
    }

    protected Fragment setupFragment(String tag, int container, Class<? extends Fragment> cls) {
        FragmentManager fm = getSupportFragmentManager();
        Fragment fragment = fm.findFragmentByTag(tag);
        if(fragment == null) {
            System.err.println("creating fragment of type " + cls.getSimpleName()); //$NON-NLS-1$
            try {
                fragment = cls.newInstance();
            } catch (InstantiationException e) {
                return null;
            } catch (IllegalAccessException e) {
                return null;
            }

            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            if (container == 0)
                ft.add(fragment, tag);
            else
                ft.replace(container, fragment, tag);
            ft.commit();
        }
        return fragment;
    }

    /**
     * @return LAYOUT_SINGLE, LAYOUT_DOUBLE, or LAYOUT_TRIPLE
     */
    public int getFragmentLayout() {
        return fragmentLayout;
    }

    /**
     * @deprecated please use the getFragmentLayout method instead
     */
    @Deprecated
    public boolean isMultipleFragments() {
        return fragmentLayout != LAYOUT_SINGLE;
    }

    private class ReminderReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Process in app notification
            Intent customIntent = intent.getExtras().getParcelable(Notifications.EXTRAS_CUSTOM_INTENT);
            long taskId = customIntent.getLongExtra(NotificationFragment.TOKEN_ID, 0);
            if (taskId > 0) {
                String text = intent.getStringExtra(Notifications.EXTRAS_TEXT);
                Dialog d = ReminderDialog.createReminderDialog(AstridActivity.this, taskId, text);
                d.show();
            }

            // Remove broadcast
            abortBroadcast();
        }

    }

}
