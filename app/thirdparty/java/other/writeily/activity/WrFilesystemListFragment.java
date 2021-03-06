/*#######################################################
 * Copyright (c) 2014 Jeff Martin
 * Copyright (c) 2015 Pedro Lafuente
 * Copyright (c) 2017-2018 Gregor Santner
 *
 * Licensed under the MIT license.
 * You can get a copy of the license text here:
 *   https://opensource.org/licenses/MIT
###########################################################*/
package other.writeily.activity;

import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.widget.SearchView;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.mobsandgeeks.adapters.Sectionizer;
import com.mobsandgeeks.adapters.SimpleSectionAdapter;

import net.gsantner.markor.R;
import net.gsantner.markor.activity.DocumentActivity;
import net.gsantner.markor.ui.FileInfoDialog;
import net.gsantner.markor.ui.FilesystemDialogCreator;
import net.gsantner.markor.util.AppCast;
import net.gsantner.markor.util.AppSettings;
import net.gsantner.markor.util.ContextUtils;
import net.gsantner.markor.util.DocumentIO;
import net.gsantner.markor.util.PermissionChecker;
import net.gsantner.opoc.activity.GsFragmentBase;
import net.gsantner.opoc.ui.FilesystemDialogData;
import net.gsantner.opoc.util.FileUtils;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import butterknife.BindView;
import butterknife.OnItemClick;
import other.writeily.model.WrMarkorSingleton;
import other.writeily.ui.WrConfirmDialog;
import other.writeily.ui.WrCreateFolderDialog;
import other.writeily.ui.WrFilesystemListAdapter;
import other.writeily.ui.WrRenameDialog;

import static android.content.Context.SEARCH_SERVICE;

@SuppressWarnings("all")
public class WrFilesystemListFragment extends GsFragmentBase {
    public static final String FRAGMENT_TAG = "WrFilesystemListFragment";
    public static final int SORT_BY_DATE = 0;
    public static final int SORT_BY_NAME = 1;
    public static final int SORT_BY_FILESIZE = 2;

    @BindView(R.id.filesystemlist__fragment__listview)
    public ListView _filesListView;

    @BindView(R.id.filesystemlist__fragment__background_hint_text)
    public TextView _backgroundHintText;

    private WrFilesystemListAdapter _filesAdapter;


    private SearchView _searchView;
    private MenuItem _searchItem;

    private ArrayList<File> _filesCurrentlyShown = new ArrayList<>();
    private ArrayList<File> _selectedItems = new ArrayList<>();
    private SimpleSectionAdapter<File> _simpleSectionAdapter;
    private WrMarkorSingleton _markorSingleton;
    private ActionMode _actionMode;
    private File _currentDir;
    private File _rootDir;
    private Sectionizer<File> _sectionizer = fileObj -> {
        try {
            return (fileObj == null
                    ? getString(R.string.files)
                    : (getString(fileObj.isDirectory() ? R.string.folders : R.string.files))
            );
        } catch (Exception ex) {
            return "Files";
        }
    };

    @Override
    protected int getLayoutResId() {
        return R.layout.filesystemlist__fragment;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context c = getContext();
        _filesAdapter = new WrFilesystemListAdapter(c, 0, _filesCurrentlyShown);
        _simpleSectionAdapter = new SimpleSectionAdapter<>(c, _filesAdapter,
                R.layout.text__item,
                R.id.notes_fragment_section_text, _sectionizer);

        _filesListView.setMultiChoiceModeListener(new ActionModeCallback());
        _filesListView.setAdapter(_simpleSectionAdapter);
        _rootDir = AppSettings.get().getNotebookDirectory();
    }

    @Override
    public void onResume() {
        super.onResume();
        _markorSingleton = WrMarkorSingleton.getInstance();
        File possiblyNewRootDir = AppSettings.get().getNotebookDirectory();
        if (possiblyNewRootDir != _rootDir) {
            _rootDir = possiblyNewRootDir;
            _currentDir = possiblyNewRootDir;
        }
        final int scroll_pos = _filesListView.getFirstVisiblePosition();
        final int scroll_pad = _filesListView.getTop() - _filesListView.getPaddingTop();
        retrieveCurrentFolder();
        listFilesInDirectory(getCurrentDir(), true);
        if (scroll_pos < _filesListView.getAdapter().getCount()) {
            _filesListView.postDelayed(() -> _filesListView.setSelectionFromTop(scroll_pos, scroll_pad), 200);
        }

        Context c = getContext();
        if (c != null) {
            LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(c);
            lbm.registerReceiver(_localBroadcastReceiver, AppCast.getLocalBroadcastFilter());
        }
    }

    private void retrieveCurrentFolder() {
        AppSettings appSettings = AppSettings.get();
        String rememberedDir = appSettings.getLastOpenedDirectory();
        _currentDir = (rememberedDir != null) ? new File(rememberedDir) : null;
        // Two-fold check, in case user doesn't have the preference to remember directories enabled
        // This code remembers last directory WITHIN the app (not leaving it)
        if (_currentDir == null) {
            _currentDir = (_markorSingleton.getNotesLastDirectory() != null) ? _markorSingleton.getNotesLastDirectory() : _rootDir;
        }
    }

    private BroadcastReceiver _localBroadcastReceiver = new BroadcastReceiver() {
        @SuppressWarnings("unchecked")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action == null ? "" : action) {
                case AppCast.CREATE_FOLDER.ACTION: {
                    File file = new File(intent.getStringExtra(AppCast.CREATE_FOLDER.EXTRA_PATH));
                    if (!file.exists() && file.mkdirs()) {
                        listFilesInDirectory(getCurrentDir(), true);
                    }
                    return;
                }

                case AppCast.VIEW_FOLDER_CHANGED.ACTION: {
                    File currentDir = new File(intent.getStringExtra(AppCast.VIEW_FOLDER_CHANGED.EXTRA_PATH));
                    if (intent.getBooleanExtra(AppCast.VIEW_FOLDER_CHANGED.EXTRA_FORCE_RELOAD, false)) {
                        listFilesInDirectory(currentDir, false);
                    }
                    return;
                }
            }
        }
    };

    private void saveCurrentFolder() {
        AppSettings appSettings = AppSettings.get();
        String saveDir = (_currentDir == null) ? _rootDir.getAbsolutePath() : _currentDir.getAbsolutePath();
        appSettings.setLastOpenedDirectory(saveDir);
        _markorSingleton.setNotesLastDirectory(_currentDir);
    }

    private void confirmDelete() {
        final ArrayList<File> itemsToDelete = new ArrayList<>(_selectedItems);
        String message = String.format(getString(R.string.do_you_really_want_to_delete_this_witharg), getResources().getQuantityString(R.plurals.documents, itemsToDelete.size()));
        WrConfirmDialog confirmDialog = WrConfirmDialog.newInstance(
                getString(R.string.confirm_delete), message, itemsToDelete,
                new WrConfirmDialog.ConfirmDialogCallback() {
                    @Override
                    public void onConfirmDialogAnswer(boolean confirmed, Serializable data) {
                        if (confirmed) {
                            WrMarkorSingleton.getInstance().deleteSelectedItems(itemsToDelete);
                            listFilesInDirectory(getCurrentDir(), true);
                            finishActionMode();
                        }
                    }
                });
        confirmDialog.show(getActivity().getSupportFragmentManager(), WrConfirmDialog.FRAGMENT_TAG);
    }

    private void promptForMoveDirectory() {
        final ArrayList<File> filesToMove = new ArrayList<>(_selectedItems);
        FilesystemDialogCreator.showFolderDialog(new FilesystemDialogData.SelectionListenerAdapter() {
            @Override
            public void onFsSelected(String request, File file) {
                super.onFsSelected(request, file);
                WrMarkorSingleton.getInstance().moveSelectedNotes(filesToMove, file.getAbsolutePath());
                listFilesInDirectory(getCurrentDir(), true);
                finishActionMode();
            }

            @Override
            public void onFsDialogConfig(FilesystemDialogData.Options opt) {
                opt.titleText = R.string.move;
                opt.rootFolder = AppSettings.get().getNotebookDirectory();
            }
        }, getActivity().getSupportFragmentManager(), getActivity());
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.filesystem__menu, menu);

        _searchItem = menu.findItem(R.id.action_search);
        _searchView = (SearchView) _searchItem.getActionView();

        SearchManager searchManager = (SearchManager) _searchView.getContext().getSystemService(SEARCH_SERVICE);
        _searchView.setSearchableInfo(searchManager.getSearchableInfo(getActivity().getComponentName()));
        _searchView.setQueryHint(getString(R.string.search_documents));
        if (_searchView != null) {
            _searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    if (query != null) {
                        if (isVisible())
                            search(query);
                    }
                    return false;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    if (newText != null) {
                        if (isVisible()) {
                            if (newText.equalsIgnoreCase("")) {
                                clearSearchFilter();
                            } else {
                                search(newText);
                            }
                        }
                    }
                    return false;
                }
            });
            _searchView.setOnQueryTextFocusChangeListener((v, hasFocus) -> {
                MenuItem item = menu.findItem(R.id.action_import);
                if (item != null) {
                    item.setVisible(hasFocus);
                }
                if (!hasFocus) {
                    _searchItem.collapseActionView();
                }
            });
        }
        ContextUtils cu = ContextUtils.get();

        cu.tintMenuItems(menu, true, Color.WHITE);
        cu.setSubMenuIconsVisiblity(menu, true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        PermissionChecker permc = new PermissionChecker(getActivity());

        switch (item.getItemId()) {
            case R.id.action_sort_by_name: {
                AppSettings.get().setSortMethod(WrFilesystemListFragment.SORT_BY_NAME);
                sortAdapter();
                return true;
            }
            case R.id.action_sort_by_date: {
                AppSettings.get().setSortMethod(WrFilesystemListFragment.SORT_BY_DATE);
                sortAdapter();
                return true;
            }
            case R.id.action_sort_by_filesize: {
                AppSettings.get().setSortMethod(WrFilesystemListFragment.SORT_BY_FILESIZE);
                sortAdapter();
                return true;
            }
            case R.id.action_sort_reverse: {
                item.setChecked(!item.isChecked());
                AppSettings.get().setSortReverse(item.isChecked());
                sortAdapter();
                return true;
            }
            case R.id.action_import: {
                if (permc.mkdirIfStoragePermissionGranted()) {
                    showImportDialog();
                }
                return true;
            }
            case R.id.action_create_folder: {
                showCreateFolderDialog();
                return true;
            }
            case R.id.action_refresh: {
                listFilesInDirectory(getCurrentDir(), true);
                return true;
            }
        }
        return false;
    }

    public void listFilesInDirectory(File directory, boolean sendBroadcast) {
        _selectedItems.clear();
        reloadFiles(directory);
        showEmptyDirHintIfEmpty();
        sortAdapter();
        clearSearchFilter();
        if (sendBroadcast) {
            AppCast.VIEW_FOLDER_CHANGED.send(getActivity(), directory.getAbsolutePath(), true);
        }
    }


    private void reloadFiles(File directory) {

        try {
            // Load from SD card
            _filesCurrentlyShown = WrMarkorSingleton.getInstance().addMarkdownFilesFromDirectory(directory, new ArrayList<>());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void reloadAdapter() {
        Context c = getContext();
        if (_filesAdapter != null && c != null) {
            _filesAdapter = new WrFilesystemListAdapter(c, 0, _filesCurrentlyShown);
            _simpleSectionAdapter =
                    new SimpleSectionAdapter<>(c
                            , _filesAdapter, R.layout.text__item
                            , R.id.notes_fragment_section_text, _sectionizer);
            _filesListView.setAdapter(_simpleSectionAdapter);
            _simpleSectionAdapter.notifyDataSetChanged();
        }
    }

    public void goDirectoryUp() {
        if (_currentDir != null) {
            _currentDir = _currentDir.getParentFile();
        }

        listFilesInDirectory(getCurrentDir(), true);
    }

    private void showEmptyDirHintIfEmpty() {
        if (isAdded()) {
            if (_markorSingleton.isDirectoryEmpty(_filesCurrentlyShown)) {
                _backgroundHintText.setVisibility(View.VISIBLE);
                _backgroundHintText.setText(getString(R.string.empty_directory));
            } else {
                _backgroundHintText.setVisibility(View.INVISIBLE);
            }
        }
    }

    public File getCurrentDir() {
        return (_currentDir == null) ? getRootDir() : _currentDir.getAbsoluteFile();
    }

    public File getRootDir() {
        return _rootDir.getAbsoluteFile();
    }

    public void finishActionMode() {
        if (_actionMode != null && _selectedItems != null) {
            _actionMode.finish();
            _selectedItems.clear();
        }
    }

    private void importFile(final File file) {
        if (new File(getCurrentDir().getAbsolutePath(), file.getName()).exists()) {
            String message = getString(R.string.file_already_exists_overwerite) + "\n[" + file.getName() + "]";
            // Ask if overwriting is okay
            WrConfirmDialog d = WrConfirmDialog.newInstance(
                    getString(R.string.confirm_overwrite), message, file, (WrConfirmDialog.ConfirmDialogCallback) (confirmed, data) -> {
                        if (confirmed) {
                            importFileToCurrentDirectory(getActivity(), file);
                        }
                    });
            if (getFragmentManager() != null) {
                d.show(getFragmentManager(), WrConfirmDialog.FRAGMENT_TAG);
            }
        } else {
            // Import
            importFileToCurrentDirectory(getActivity(), file);
        }
    }

    private void importFileToCurrentDirectory(Context context, File sourceFile) {
        FileUtils.copyFile(sourceFile, new File(getCurrentDir().getAbsolutePath(), sourceFile.getName()));
        Toast.makeText(context, getString(R.string.import_) + ": " + sourceFile.getName(), Toast.LENGTH_LONG).show();
    }

    private void showImportDialog() {
        FilesystemDialogCreator.showFileDialog(new FilesystemDialogData.SelectionListenerAdapter() {
            @Override
            public void onFsSelected(String request, File file) {
                importFile(file);
                listFilesInDirectory(getCurrentDir(), true);
            }

            @Override
            public void onFsMultiSelected(String request, File... files) {
                for (File file : files) {
                    importFile(file);
                }
                listFilesInDirectory(getCurrentDir(), true);
            }

            @Override
            public void onFsDialogConfig(FilesystemDialogData.Options opt) {
                opt.titleText = R.string.import_from_device;
                opt.doSelectMultiple = true;
                opt.doSelectFile = true;
                opt.doSelectFolder = true;
            }
        }, getFragmentManager(), getActivity());
    }

    public void showCreateFolderDialog() {
        FragmentManager supFragManager;
        Bundle args = new Bundle();
        args.putString(WrCreateFolderDialog.CURRENT_DIRECTORY_DIALOG_KEY, getCurrentDir().getAbsolutePath());

        WrCreateFolderDialog createFolderDialog = new WrCreateFolderDialog();
        createFolderDialog.setArguments(args);
        if ((supFragManager = getFragmentManager()) != null) {
            createFolderDialog.show(supFragManager, WrCreateFolderDialog.FRAGMENT_TAG);
        }
    }

    /**
     * Search
     **/
    public void search(CharSequence query) {
        if (query.length() > 0) {
            _filesAdapter.getFilter().filter(query);
            _simpleSectionAdapter.notifyDataSetChanged();
        }
    }

    public void clearSearchFilter() {
        _filesAdapter.getFilter().filter("");
        _simpleSectionAdapter.notifyDataSetChanged();
        reloadAdapter();
    }

    public static void sortFolder(ArrayList<File> filesCurrentlyShown) {
        final int sortMethod = AppSettings.get().getSortMethod();
        final boolean sortReverse = AppSettings.get().isSortReverse();
        int count = filesCurrentlyShown.size();
        int lastFolderIndex = 0;
        for (int i = 0; i < count; i++) {
            if (filesCurrentlyShown.get(i).isDirectory()) {
                lastFolderIndex++;
            }
        }

        Comparator<File> comparator = new Comparator<File>() {
            @Override
            public int compare(File file, File other) {
                if (sortReverse) {
                    File swap = file;
                    file = other;
                    other = swap;
                }

                switch (sortMethod) {
                    case SORT_BY_NAME:
                        return new File(file.getAbsolutePath().toLowerCase()).compareTo(
                                new File(other.getAbsolutePath().toLowerCase()));
                    case SORT_BY_DATE:
                        return Long.valueOf(other.lastModified()).compareTo(file.lastModified());
                    case SORT_BY_FILESIZE:
                        if (file.isDirectory() && other.isDirectory()) {
                            return other.list().length - file.list().length;
                        }
                        return Long.valueOf(other.length()).compareTo(file.length());
                }
                return file.compareTo(other);
            }
        };

        Collections.sort(filesCurrentlyShown.subList(0, lastFolderIndex), comparator);
        Collections.sort(filesCurrentlyShown.subList(lastFolderIndex, count), comparator);

    }

    public void sortAdapter() {
        sortFolder(_filesCurrentlyShown);
        reloadAdapter();
    }

    public boolean isCurrentDirectoryNotebookDirectory() {
        return _currentDir == null || _rootDir == null ||
                _currentDir.getAbsolutePath().equalsIgnoreCase(_rootDir.getAbsolutePath());
    }

    @Override
    public String getFragmentTag() {
        return FRAGMENT_TAG;
    }

    @Override
    public boolean onBackPressed() {
        if (_searchView.isFocused()) {
            _searchView.clearFocus();
            _searchView.setSelected(false);
            return true;
        }
        if (!_searchView.getQuery().toString().isEmpty() || !_searchView.isIconified()) {
            _searchView.setQuery("", false);
            _searchView.setIconified(true);
            _searchItem.collapseActionView();
            return true;
        }

        if (!isCurrentDirectoryNotebookDirectory()) {
            goDirectoryUp();
            return true;
        }

        return false;
    }

    private class ActionModeCallback implements ListView.MultiChoiceModeListener {

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.filesystem__context_menu, menu);
            _cu.tintMenuItems(menu, true, Color.WHITE);
            _actionMode = mode;
            _actionMode.setTitle(getResources().getString(R.string.select_entries));
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.context_menu_delete:
                    confirmDelete();
                    finishActionMode();
                    return true;
                case R.id.context_menu_move:
                    promptForMoveDirectory();
                    finishActionMode();
                    return true;
                case R.id.context_menu_rename:
                    if (!_selectedItems.isEmpty()) {
                        promptForNewName(_selectedItems.get(0));
                    }
                    finishActionMode();
                    return true;
                case R.id.context_menu_info:
                    if (!_selectedItems.isEmpty()) {
                        FileInfoDialog fileInfoDialog = FileInfoDialog.newInstance(_selectedItems.get(0));
                        fileInfoDialog.show(getFragmentManager(), FileInfoDialog.FRAGMENT_TAG);
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void promptForNewName(File file) {
            WrRenameDialog renameDialog = WrRenameDialog.newInstance(file);
            renameDialog.show(getFragmentManager(), WrRenameDialog.FRAGMENT_TAG);
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
        }

        @Override
        public void onItemCheckedStateChanged(ActionMode actionMode, int i, long l, boolean checked) {

            switch (_filesListView.getCheckedItemCount()) {
                case 0:
                    actionMode.setSubtitle(null);
                    setMultiActionButtonVisibility(actionMode, false);
                    break;
                case 1:
                    actionMode.setSubtitle(getResources().getString(R.string.one_item_selected));
                    manageClickedVIew(i, checked);
                    setMultiActionButtonVisibility(actionMode, true);
                    break;
                default:
                    manageClickedVIew(i, checked);
                    actionMode.setSubtitle(String.format(getResources().getString(R.string.items_selected_witharg), _filesListView.getCheckedItemCount()));
                    setMultiActionButtonVisibility(actionMode, false);
                    break;
            }
        }

        @SuppressWarnings("RedundantCast")
        private void manageClickedVIew(int i, boolean checked) {
            if (checked) {
                _selectedItems.add((File) _simpleSectionAdapter.getItem(i));
            } else {
                _selectedItems.remove((File) _simpleSectionAdapter.getItem(i));
            }
        }

        private void setMultiActionButtonVisibility(ActionMode actionMode, boolean visible) {
            Menu menu = actionMode.getMenu();
            menu.findItem(R.id.context_menu_rename).setVisible(visible);
            menu.findItem(R.id.context_menu_info).setVisible(visible);
        }
    } // End: Action Mode callback

    @OnItemClick(R.id.filesystemlist__fragment__listview)
    public void onNotesItemClickListener(AdapterView<?> adapterView, View view, int i, long l) {
        File clickedFile = (File) _simpleSectionAdapter.getItem(i);
        Context context = view.getContext();

        // Refresh list if directory, else import
        if (clickedFile.isDirectory()) {
            _currentDir = clickedFile;
            listFilesInDirectory(clickedFile, true);
        } else {
            saveCurrentFolder();
            Intent intent = new Intent(context, DocumentActivity.class);
            intent.putExtra(DocumentIO.EXTRA_PATH, clickedFile);
            intent.putExtra(DocumentIO.EXTRA_PATH_IS_FOLDER, false);
            startActivity(intent);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        finishActionMode();
    }
}
