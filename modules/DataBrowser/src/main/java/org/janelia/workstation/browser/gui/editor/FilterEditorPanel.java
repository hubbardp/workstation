package org.janelia.workstation.browser.gui.editor;

import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import org.apache.commons.lang3.StringUtils;
import org.janelia.model.access.domain.search.FacetValue;
import org.janelia.model.domain.*;
import org.janelia.model.domain.gui.search.Filter;
import org.janelia.model.domain.gui.search.Filtering;
import org.janelia.model.domain.gui.search.criteria.*;
import org.janelia.model.domain.interfaces.HasIdentifier;
import org.janelia.model.domain.sample.LSMImage;
import org.janelia.model.domain.sample.Sample;
import org.janelia.workstation.browser.actions.ExportResultsAction;
import org.janelia.workstation.browser.gui.components.DomainExplorerTopComponent;
import org.janelia.workstation.browser.gui.dialogs.EditCriteriaDialog;
import org.janelia.workstation.browser.gui.listview.PaginatedDomainResultsPanel;
import org.janelia.workstation.browser.gui.listview.table.DomainObjectTableViewer;
import org.janelia.workstation.common.gui.support.*;
import org.janelia.workstation.common.gui.support.buttons.DropDownButton;
import org.janelia.workstation.common.nodes.FilterNode;
import org.janelia.workstation.core.actions.ViewerContext;
import org.janelia.workstation.core.activity_logging.ActivityLogHelper;
import org.janelia.workstation.core.api.ClientDomainUtils;
import org.janelia.workstation.core.api.DomainMgr;
import org.janelia.workstation.core.api.DomainModel;
import org.janelia.workstation.core.events.Events;
import org.janelia.workstation.core.events.model.DomainObjectChangeEvent;
import org.janelia.workstation.core.events.model.DomainObjectInvalidationEvent;
import org.janelia.workstation.core.events.model.DomainObjectRemoveEvent;
import org.janelia.workstation.core.events.selection.ChildSelectionModel;
import org.janelia.workstation.core.events.selection.DomainObjectEditSelectionModel;
import org.janelia.workstation.core.events.selection.DomainObjectSelectionModel;
import org.janelia.workstation.core.events.selection.ViewerContextChangeEvent;
import org.janelia.workstation.core.model.ImageModel;
import org.janelia.workstation.core.model.search.DomainObjectSearchResults;
import org.janelia.workstation.core.model.search.ResultPage;
import org.janelia.workstation.core.model.search.SearchConfiguration;
import org.janelia.workstation.core.model.search.SearchResults;
import org.janelia.workstation.core.nodes.DomainObjectNode;
import org.janelia.workstation.core.util.ConcurrentUtils;
import org.janelia.workstation.core.util.StringUtilsExtra;
import org.janelia.workstation.core.workers.IndeterminateProgressMonitor;
import org.janelia.workstation.core.workers.SimpleWorker;
import org.janelia.workstation.integration.util.FrameworkAccess;
import org.perf4j.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.janelia.workstation.core.api.DomainMgr.getDomainMgr;

/**
 * The Filter Editor is the main search GUI in the Workstation. Users can create, save, and load filters 
 * into this panel. The filter is executed every time it changes, and shows results in an embedded 
 * PaginatedResultsPanel. 
 *
 * @author <a href="mailto:rokickik@janelia.hhmi.org">Konrad Rokicki</a>
 */
public class FilterEditorPanel 
        extends DomainObjectEditorPanel<Filtering,DomainObject>
        implements SearchProvider, PreferenceSupport {

    private static final Logger log = LoggerFactory.getLogger(FilterEditorPanel.class);

    public static final int EXPORT_PAGE_SIZE = 1000;
    
    // UI Settings
    public static final String DEFAULT_FILTER_NAME = "Unsaved Search";
    public static final Class<?> DEFAULT_SEARCH_CLASS = Sample.class;
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy/MM/dd");
    
    // Utilities
    private final Debouncer debouncer = new Debouncer();
    private final Debouncer reloadDebouncer = new Debouncer();
    private final Debouncer refreshDebouncer = new Debouncer();
    
    // UI Elements
    private final ConfigPanel configPanel;
    private final JButton saveButton;
    private final JButton saveAsButton;
    private final PaginatedDomainResultsPanel resultsPanel;
    private final DropDownButton typeCriteriaButton;
    private final DropDownButton addCriteriaButton;
    private final SmartTextField searchBox;
    private final JButton infoButton;

    // State
    private DomainObjectSelectionModel selectionModel = new DomainObjectSelectionModel();
    private DomainObjectEditSelectionModel editSelectionModel = new DomainObjectEditSelectionModel();
    private FilterNode filterNode;
    private Filter filter;    
    private boolean dirty = false;
    private SearchConfiguration searchConfig;
    
    // Results
    private DomainObjectSearchResults searchResults;

    public FilterEditorPanel() {

        this.saveButton = new JButton("Save");
        saveButton.addActionListener(e -> {
            if (filter==null) throw new IllegalStateException("Cannot save null filter");
            SimpleWorker worker = new SimpleWorker() {

                private Filter savedFilter;

                @Override
                protected void doStuff() throws Exception {
                    Filter filterToSave = filter;
                    if (filterToSave.getId()!=null) {
                        // What we have is a clone of a filter, so we need to get the canonical instance
                        filterToSave = DomainMgr.getDomainMgr().getModel().getDomainObject(filter);
                        filterToSave.setCriteriaList(filter.getCriteriaList());
                        filterToSave.setSearchClass(filter.getSearchClass());
                        filterToSave.setSearchString(filter.getSearchString());
                        log.info("Saving filter '{}' with id {}",filterToSave.getName(),filterToSave.getId());
                    }
                    else {
                        log.info("Creating filter '{}'",filterToSave.getName());
                    }

                    savedFilter = DomainMgr.getDomainMgr().getModel().save(filterToSave);
                }

                @Override
                protected void hadSuccess() {
                    setFilter(savedFilter);
                    savePreferences();
                    saveButton.setVisible(false);
                }

                @Override
                protected void hadError(Throwable error) {
                    FrameworkAccess.handleException(error);
                }
            };
            worker.execute();

        });
        
        this.saveAsButton = new JButton("Save As");
        saveAsButton.addActionListener(e -> {

            String name = filter.getName().equals(DEFAULT_FILTER_NAME)?null:filter.getName();
            String newName = null;
            while (StringUtils.isEmpty(newName)) {
                newName = (String) JOptionPane.showInputDialog(FrameworkAccess.getMainFrame(),
                        "Search Name:\n", "Save Search", JOptionPane.PLAIN_MESSAGE, null, null, name);
                log.info("newName:" + newName);
                if (newName == null) {
                    // User chose "Cancel"
                    return;
                }
                if (StringUtils.isBlank(newName)) {
                    JOptionPane.showMessageDialog(FrameworkAccess.getMainFrame(), "Filter name cannot be blank");
                    return;
                }
            }

            final String finalName = newName;
            final boolean isNewFilter = filter.getId()==null;

            SimpleWorker worker = new SimpleWorker() {

                @Override
                protected void doStuff() throws Exception {
                    Filter savedFilter = filter;
                    if (!isNewFilter) {
                        // This filter is already saved, duplicate it so that we don't overwrite the existing one
                        savedFilter = DomainUtils.cloneFilter(filter);
                    }
                    savedFilter.setName(finalName);
                    DomainModel model = getDomainMgr().getModel();
                    savedFilter = model.save(savedFilter);
                    model.addChild(model.getDefaultWorkspace(), savedFilter);
                    setFilter(savedFilter);
                    savePreferences();
                }

                @Override
                protected void hadSuccess() {
                    // Wait for events to resolve
                    SwingUtilities.invokeLater(() -> {
                        // Select the filter and force it to reload
                        DomainExplorerTopComponent.getInstance().selectAndNavigateNodeById(filter.getId());
                    });
                }

                @Override
                protected void hadError(Throwable error) {
                    FrameworkAccess.handleException(error);
                }
            };

            worker.execute();

        });
        
        this.typeCriteriaButton = new DropDownButton();
        this.addCriteriaButton = new DropDownButton("Add Criteria...");
        this.searchBox = new SmartTextField("SEARCH_HISTORY");
        searchBox.setPreferredSize(new Dimension(200, 30));
        searchBox.setToolTipText("Enter search terms...");

        infoButton = new JButton(Icons.getIcon("info.png"));
        infoButton.setMargin(new Insets(0,2,0,2));
        infoButton.setOpaque(false);
        infoButton.setContentAreaFilled(false);
        infoButton.setBorderPainted(false);
        infoButton.addActionListener(e -> {
            // TODO: make a custom help page later
            try {
                if (!DesktopApi.browseDesktop(new URI("http://lucene.apache.org/core/old_versioned_docs/versions/3_5_0/queryparsersyntax.html"))) {
                    JOptionPane.showMessageDialog(
                            FrameworkAccess.getMainFrame(),
                            "Cannot open URL. Desktop API is not supported on this platform.",
                            "Error",
                            JOptionPane.ERROR_MESSAGE,
                            null
                    );
                }
            }
            catch (Exception ex) {
                FrameworkAccess.handleException(ex);
            }
        });

        AbstractAction mySearchAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchBox.addCurrentTextToHistory();
                refreshSearchResults(true);
            }
        };

        this.resultsPanel = new PaginatedDomainResultsPanel(getSelectionModel(), getEditSelectionModel(), this, this) {
            @Override
            protected ResultPage<DomainObject, Reference> getPage(SearchResults<DomainObject, Reference> searchResults, int page) throws Exception {
                return searchResults.getPage(page);
            }
            @Override
            public Reference getId(DomainObject object) {
                return Reference.createFor(object);
            }
            @Override
            protected void viewerContextChanged() {
                Events.getInstance().postOnEventBus(new ViewerContextChangeEvent(this, getViewerContext()));
            }
        };
        resultsPanel.addMouseListener(new MouseForwarder(this, "PaginatedResultsPanel->FilterEditorPanel"));

        configPanel = new ConfigPanel(true);
        configPanel.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0,true), "enterAction");
        configPanel.getActionMap().put("enterAction", mySearchAction);

        setLayout(new BorderLayout());
        add(configPanel, BorderLayout.NORTH);
        add(resultsPanel, BorderLayout.CENTER);
    }

    private void setFilter(Filtering canonicalFilter) {

        // Clone the filter so that we don't modify the one in the cache
        this.filter = DomainUtils.cloneFilter(canonicalFilter);
        filter.setName(canonicalFilter.getName());

        if (Filter.class.equals(canonicalFilter.getClass())) {
            // We can only override the given object with a cloned filter (e.g. use the "Save" action) if it's a Filter to begin with
            filter.setId(canonicalFilter.getId());
        }
        this.searchConfig = new SearchConfiguration(filter, DomainObjectSearchResults.PAGE_SIZE);
    }

    @Override
    public void loadDomainObjectNode(DomainObjectNode<Filtering> filterNode, boolean isUserDriven, Callable<Void> success) {
        this.filterNode = (FilterNode)filterNode;
        loadDomainObject(filterNode.getDomainObject(), isUserDriven, success);
    }

    public void loadDomainObject(Filtering filter, final boolean isUserDriven, final Callable<Void> success) {

        if (filter==null) return;
        
        if (!debouncer.queue(success)) {
            log.info("Skipping load, since there is one already in progress");
            return;
        }

        log.info("Loading '{}' ({})", filter.getName(), filter);

        if (filter.getName()==null) {
            filter.setName(DEFAULT_FILTER_NAME);
            // Do not run a new search automatically
        }
        
        log.debug("loadDomainObject(Filter:{})",filter.getName());
        final StopWatch w = new StopWatch();

        getSelectionModel().setParentObject(filter);
        this.dirty = false;
        setFilter(filter);
        loadPreferences();

        try {
            updateView();
            
            configPanel.removeAllTitleComponents();
            if (ClientDomainUtils.hasWriteAccess(filter)) {
	            configPanel.addTitleComponent(saveButton, false, true);
            }
            configPanel.addTitleComponent(saveAsButton, false, true);
            configPanel.setExpanded(filter.getId()==null);

            refreshSearchResults(isUserDriven, () -> {
                searchBox.requestFocus();
                debouncer.success();
                return null;
            }, () -> {
                debouncer.failure();
                return null;
            });
        }
        catch (Exception e) {
            FrameworkAccess.handleException(e);
        }

        ActivityLogHelper.logElapsed("FilterEditorPanel.loadDomainObject", filter, w);
    }
    
    private void refreshSearchResults(boolean isUserDriven) {
        refreshSearchResults(isUserDriven, null);
    }
    
    private void refreshSearchResults(boolean isUserDriven, final Callable<Void> success) {
        refreshDebouncer.queue(success);
        refreshSearchResults(isUserDriven, () -> {
            refreshDebouncer.success();
            return null;
        }, () -> {
            refreshDebouncer.failure();
            return null;
        });
    }
    
    private void refreshSearchResults(final boolean isUserDriven, final Callable<Void> success, final Callable<Void> failure) {
        log.info("Refreshing search results");
        
        String inputFieldValue = searchBox.getText();
        if (!StringUtilsExtra.areEqual(filter.getSearchString(), inputFieldValue)) {
            filter.setSearchString(inputFieldValue);
            dirty = true;
        }

        saveButton.setVisible(dirty && filter.getId()!=null && !filter.getName().equals(DEFAULT_FILTER_NAME));
        
        performSearch(isUserDriven, success, failure);
    }
    
    public synchronized void performSearch(final boolean isUserDriven, final Callable<Void> success, final Callable<Void> failure) {

        if (searchConfig==null || searchConfig.getSearchClass()==null) return;
        
        log.debug("Performing search with isUserDriven={}",isUserDriven);
        final StopWatch w = new StopWatch();
        resultsPanel.showLoadingIndicator();
        
        SimpleWorker worker = new SimpleWorker() {

            @Override
            protected void doStuff() throws Exception {
                searchResults = searchConfig.performSearch();
            }

            @Override
            protected void hadSuccess() {
                try {
                    resultsPanel.showSearchResults(searchResults, isUserDriven, () -> {
                        updateView();
                        ConcurrentUtils.invokeAndHandleExceptions(success);
                        ActivityLogHelper.logElapsed("FilterEditorPanel.performSearch", w);
                        return null;
                    });
                }
                catch (Exception e) {
                    hadError(e);
                }
            }

            @Override
            protected void hadError(Throwable error) {
                showNothing();
                ConcurrentUtils.invokeAndHandleExceptions(failure);
                FrameworkAccess.handleException(error);
            }
        };

        worker.execute();
    }

    public void showNothing() {
        resultsPanel.showNothing();
    }
    
    private void updateView() {

        searchBox.setText(filter.getSearchString());

        final String currType = DomainUtils.getTypeName(searchConfig.getSearchClass());
        typeCriteriaButton.setText("Result Type: " + currType);

        typeCriteriaButton.removeAll();
        ButtonGroup typeGroup = new ButtonGroup();
        for (final Class<? extends DomainObject> searchClass : DomainUtils.getSearchClasses()) {
            final String type = DomainUtils.getTypeName(searchClass);
            JMenuItem menuItem = new JRadioButtonMenuItem(type, searchClass.equals(searchConfig.getSearchClass()));
            menuItem.addActionListener(e -> {
                dirty = true;
                searchConfig.setSearchClass(searchClass);
                updateView();
                refreshSearchResults(true);
            });
            typeGroup.add(menuItem);
            typeCriteriaButton.addMenuItem(menuItem);
        }

        configPanel.setTitle(filter.getName());
        
        // Update filters
        configPanel.removeAllConfigComponents();
        configPanel.addConfigComponent(typeCriteriaButton);
        configPanel.addConfigComponent(searchBox);
        configPanel.addConfigComponent(infoButton);

        for (Criteria criteria : filter.getCriteriaList()) {
            if (criteria instanceof TreeNodeCriteria) {
                DropDownButton customCriteriaButton = createCustomCriteriaButton((TreeNodeCriteria)criteria);
                configPanel.addConfigComponent(customCriteriaButton);
            }
        }

        for(DomainObjectAttribute attr : searchConfig.getDomainObjectAttributes()) {
            if (attr.getFacetKey()!=null) {
                log.trace("Adding facet: {}",attr.getLabel());
                
                SelectionButton<FacetValue> facetButton = new SelectionButton<FacetValue>(attr.getLabel()) {

                    @Override
                    public Collection<FacetValue> getValues() {
                        return searchConfig.getFacetValues(attr.getFacetKey());
                    }

                    @Override
                    public Set<FacetValue> getSelectedValues() {
                        return getSelectedFacetValues(attr);
                    }

                    @Override
                    public String getName(FacetValue value) {
                        return value.getValue();
                    }
                    
                    @Override
                    public String getLabel(FacetValue value) {
                        return value.getValue()+" ("+value.getCount()+" items)";
                    }

                    @Override
                    public boolean isHidden(FacetValue value) {
                        return value.getCount()==0;
                    }

                    @Override
                    protected void selectAll() {
                        for(FacetValue value : getValues()) {
                            updateFacet(attr.getName(), value.getValue(), true);
                        }
                        dirty = true;
                        refreshSearchResults(true);
                    }
                    @Override
                    protected void clearSelected() {
                        updateFacet(attr.getName(), null, false);
                        dirty = true;
                        refreshSearchResults(true);
                    }

                    @Override
                    protected void updateSelection(FacetValue value, boolean selected) {
                        updateFacet(attr.getName(), value.getValue(), selected);
                        dirty = true;
                        refreshSearchResults(true);
                    }
                    
                };
                
                configPanel.addConfigComponent(facetButton);
            }
        }

        for (Criteria criteria : filter.getCriteriaList()) {
            if (criteria instanceof AttributeCriteria) {
                DropDownButton customCriteriaButton = createCustomCriteriaButton((AttributeCriteria)criteria);
                if (customCriteriaButton!=null) {
                    configPanel.addConfigComponent(customCriteriaButton);
                }
            }
        }

        addCriteriaButton.removeAll();

        for (final DomainObjectAttribute attr : searchConfig.getDomainObjectAttributes()) {

            boolean found = false;
            if (filter.hasCriteria()) {
                for (Criteria criteria : filter.getCriteriaList()) {
                    if (criteria instanceof AttributeCriteria) {
                        AttributeCriteria ac = (AttributeCriteria)criteria;
                        if (ac.getAttributeName().equals(attr.getName())) {
                            found = true;
                        }
                    }
                }
            }
            
            if (found) continue;
            
            JMenuItem menuItem = new JMenuItem(attr.getLabel());
            menuItem.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    
                    AttributeCriteria criteria;
                    Class<?> attrClass = attr.getGetter().getReturnType();
                    if (attrClass.equals(Date.class)) {
                        criteria = new DateRangeCriteria();
                    }
                    else {
                        criteria = new AttributeValueCriteria();
                    }
                    
                    criteria.setAttributeName(attr.getName());
                    EditCriteriaDialog dialog = new EditCriteriaDialog();
                    criteria = (AttributeCriteria)dialog.showForCriteria(criteria, attr.getLabel());
                    
                    if (criteria!=null) {
                        filter.addCriteria(criteria);
                        dirty = true;
                        refreshSearchResults(true);
                    }
                }
            });
            addCriteriaButton.addMenuItem(menuItem);
        }
        
        configPanel.addConfigComponent(addCriteriaButton);
        configPanel.updateUI();
    }

    private DropDownButton createCustomCriteriaButton(final TreeNodeCriteria criteria) {

        DropDownButton facetButton = new DropDownButton("In: "+criteria.getTreeNodeName());

        facetButton.removeAll();

        final JMenuItem removeMenuItem = new JMenuItem("Remove");
        removeMenuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                removeTreeNodeCriteria(criteria.getTreeNodeName());
                dirty = true;
                refreshSearchResults(true);
            }
        });
        facetButton.addMenuItem(removeMenuItem);

        return facetButton;
    }

    private DropDownButton createCustomCriteriaButton(final AttributeCriteria criteria) {
        
        String label;
        final DomainObjectAttribute attr = searchConfig.getDomainObjectAttribute(criteria.getAttributeName());
        
        if (criteria instanceof AttributeValueCriteria) {
            AttributeValueCriteria avc = (AttributeValueCriteria)criteria;
            label = attr.getLabel()+": "+avc.getValue();
        }
        else if (criteria instanceof DateRangeCriteria) {
            DateRangeCriteria drc = (DateRangeCriteria)criteria;
            label = attr.getLabel()+": "+DATE_FORMAT.format(drc.getStartDate())+" - "+DATE_FORMAT.format(drc.getEndDate());
        }
        else {
            return null;
        }
        
        DropDownButton facetButton = new DropDownButton(label);
        facetButton.removeAll();
        
        final JMenuItem editMenuItem = new JMenuItem("Edit");
        editMenuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                EditCriteriaDialog dialog = new EditCriteriaDialog();
                if (dialog.showForCriteria(criteria, attr.getLabel())!=null) {
                    dirty = true;
                    refreshSearchResults(true);
                }
            }
        });
        facetButton.addMenuItem(editMenuItem);
        
        final JMenuItem removeMenuItem = new JMenuItem("Remove");
        removeMenuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                removeAttributeCriteria(criteria.getAttributeName());
                dirty = true;
                refreshSearchResults(true);
            }
        });
        facetButton.addMenuItem(removeMenuItem);
        
        return facetButton;
    }

    private void removeTreeNodeCriteria(String treeNodeName) {
        if (filter.hasCriteria()) {
            for (Iterator<Criteria> i = filter.getCriteriaList().iterator(); i.hasNext(); ) {
                Criteria criteria = i.next();
                if (criteria instanceof TreeNodeCriteria) {
                    TreeNodeCriteria tnc = (TreeNodeCriteria) criteria;
                    if (tnc.getTreeNodeName().equals(treeNodeName)) {
                        // Remove facet entirely
                        i.remove();
                    }
                }
            }
        }
    }

    private void removeAttributeCriteria(String attrName) {
        if (filter.hasCriteria()) {
            for (Iterator<Criteria> i = filter.getCriteriaList().iterator(); i.hasNext(); ) {
                Criteria criteria = i.next();
                if (criteria instanceof AttributeCriteria) {
                    AttributeCriteria ac = (AttributeCriteria) criteria;
                    if (ac.getAttributeName().equals(attrName)) {
                        // Remove facet entirely
                        i.remove();
                    }
                }
            }
        }
    }

    private Set<FacetValue> getSelectedFacetValues(DomainObjectAttribute attr) {
        if (filter==null || !filter.hasCriteria()) return new HashSet<>();
        for (Criteria criteria : filter.getCriteriaList()) {
            if (criteria instanceof FacetCriteria) {
                FacetCriteria fc = (FacetCriteria) criteria;
                if (fc.getAttributeName().equals(attr.getName())) {
                    List<FacetValue> facetValues = searchConfig.getFacetValues(attr.getFacetKey());
                    if (facetValues != null) {
                        return searchConfig.getFacetValues(attr.getFacetKey())
                                .stream()
                                .filter(facetValue -> fc.getValues().contains(facetValue.getValue()))
                                .collect(Collectors.toSet());
                    }
                }
            }
        }
        return new HashSet<>();
    }
    
    private void updateFacet(String attrName, String value, boolean addValue) {
        boolean modified = false;
        // Attempt to modify an existing facet criteria
        if (filter.hasCriteria()) {
            for (Iterator<Criteria> i = filter.getCriteriaList().iterator(); i.hasNext(); ) {
                Criteria criteria = i.next();
                if (criteria instanceof FacetCriteria) {
                    FacetCriteria fc = (FacetCriteria) criteria;
                    if (fc.getAttributeName().equals(attrName)) {
                        if (value==null) {
                            // Remove facet entirely
                            i.remove();
                        }
                        else {
                            modified = true;
                            if (addValue) {
                                fc.getValues().add(value);
                            }
                            else {
                                fc.getValues().remove(value);
                                if (fc.getValues().isEmpty()) {
                                    i.remove();
                                }
                            }
                        }
                    }
                }
            }
        }
        // Facet criteria was not found, create a new one
        if (!modified && addValue) {
            FacetCriteria fc = new FacetCriteria(); 
            fc.setAttributeName(attrName);
            fc.getValues().add(value);
            filter.addCriteria(fc);
        }
    }
    
    @Subscribe
    public void domainObjectInvalidated(DomainObjectInvalidationEvent event) {
        try {
            if (event.isTotalInvalidation()) {
                log.info("Total invalidation, reloading...");
                reload();
            }
            else {
                for (DomainObject domainObject : event.getDomainObjects()) {
                    if (searchConfig!=null && searchConfig.getSearchClass()!=null && searchConfig.getSearchClass().isAssignableFrom(domainObject.getClass())) {
                        log.info("Object with search class invalidated, reloading...");
                        reload();
                        break;
                    }
                    else if (filter!=null && filter.getId()!=null && domainObject.getId().equals(filter.getId())) {
                        log.info("Filter invalidated, reloading...");
                        reload();
                        break;
                    }
                }
            }
        } 
        catch (Exception e) {
            FrameworkAccess.handleException(e);
        }
    }

    private void reload() throws Exception {
        
        if (filter==null || filter.getId()==null) {
            // Nothing to reload, just rerun the search
            restoreState(saveState(), null);
            return;
        }

        if (!reloadDebouncer.queue()) {
            log.info("Skipping reload, since there is one already in progress");
            return;
        }

        SimpleWorker worker = new SimpleWorker() {

            Filter updatedFilter;

            @Override
            protected void doStuff() throws Exception {
                updatedFilter = getDomainMgr().getModel().getDomainObject(filter.getClass(), filter.getId());
            }

            @Override
            protected void hadSuccess() {
                if (updatedFilter!=null) {
                    if (filterNode!=null && !filterNode.getFilter().equals(updatedFilter)) {
                        filterNode.update(updatedFilter);
                    }
                    filter = updatedFilter;
                    restoreState(saveState(), () -> {
                        reloadDebouncer.success();
                        return null;
                    });
                }
                else {
                    // The filter no longer exists, or we no longer have access to it (perhaps running as a different user?)
                    // Either way, there's nothing to show.
                    showNothing();
                    reloadDebouncer.success();
                }
            }

            @Override
            protected void hadError(Throwable error) {
                FrameworkAccess.handleException(error);
                reloadDebouncer.failure();
            }
        };

        worker.execute();
    }

    public static Filter createUnsavedFilter(Class<?> searchClass, String name) {
        Filter filter = new Filter();
        filter.setSearchClass(searchClass==null?DEFAULT_SEARCH_CLASS.getName():searchClass.getName());
        if (Sample.class.equals(searchClass) || LSMImage.class.equals(searchClass)) {
            FacetCriteria facet = new FacetCriteria();
            facet.setAttributeName("sageSynced");
            facet.setValues(Sets.newHashSet("true"));
            filter.addCriteria(facet);
        }
        return filter;
    }
    
    @Override
    public String getName() {
        if (filter==null) {
            return "Filter Editor";
        }
        if (DEFAULT_FILTER_NAME.equals(filter.getName())) {
            return filter.getName();
        }
        return "Search: "+StringUtils.abbreviate(filter.getName(), 15);
    }

    @Override
    public PaginatedDomainResultsPanel getResultsPanel() {
        return resultsPanel;
    }

    @Override
    protected Filtering getDomainObject() {
        return filter;
    }

    @Override
    protected DomainObjectNode<Filtering> getDomainObjectNode() {
        return filterNode;
    }
    
    @Override
    public void activate() {
        resultsPanel.activate();
    }

    @Override
    public void deactivate() {
        resultsPanel.deactivate();
    }

    @Subscribe
    public void domainObjectRemoved(DomainObjectRemoveEvent event) {
        if (filter==null) return;
        if (event.getDomainObject().getId().equals(filter.getId())) {
            // Reset filter
            Filter newFilter = createUnsavedFilter(null, null);
            loadDomainObject(newFilter, true, null);
        }
    }

    @Subscribe
    public void domainObjectChanged(DomainObjectChangeEvent event) {
        if (searchResults!=null && searchResults.updateIfFound(event.getDomainObject())) {
            log.info("Updated search results with changed domain object: {}", event.getDomainObject());
        }
    }
    
    @Override
    public String getSortField() {
        return searchConfig.getSortCriteria();
    }

    @Override
    public void setSortField(String sortCriteria) {
        searchConfig.setSortCriteria(sortCriteria);
        savePreferences();
    }

    @Override
    public void search() {
        refreshSearchResults(true);
    }

    private void loadPreferences() {
        if (filter.getId()==null) return;
        try {
            String sortCriteriaPref = FrameworkAccess.getRemotePreferenceValue(
                    DomainConstants.PREFERENCE_CATEGORY_SORT_CRITERIA, filter.getId().toString(), null);
            if (sortCriteriaPref!=null) {
                log.info("Loaded sort criteria preference: {}", sortCriteriaPref);
                searchConfig.setSortCriteria(sortCriteriaPref);
            }
        }
        catch (Exception e) {
            log.error("Could not load sort criteria",e);
        }
    }

    private void savePreferences() {
        if (filter.getId()==null || StringUtils.isEmpty(searchConfig.getSortCriteria())) return;
        try {
            FrameworkAccess.setRemotePreferenceValue(
                    DomainConstants.PREFERENCE_CATEGORY_SORT_CRITERIA, filter.getId().toString(), searchConfig.getSortCriteria());
            log.info("Saved sort criteria preference: {}",searchConfig.getSortCriteria());
        }
        catch (Exception e) {
            log.error("Could not save sort criteria",e);
        }
    }

    @Override
    public void export() {

        SimpleWorker worker = new SimpleWorker() {

            private DomainObjectSearchResults exportSearchResults = searchResults;

            @Override
            protected void doStuff() throws Exception {
                if (!searchResults.isAllLoaded()) {
                    // If anything is unloaded, we create a new search that uses a larger page size, in order to batch the export faster.
                    SearchConfiguration exportSearchConfig = new SearchConfiguration(filter, EXPORT_PAGE_SIZE);
                    exportSearchResults = exportSearchConfig.performSearch();
                }
            }

            @Override
            protected void hadSuccess() {
                DomainObjectTableViewer viewer = null;
                if (resultsPanel.getViewer() instanceof DomainObjectTableViewer) {
                    viewer = (DomainObjectTableViewer)resultsPanel.getViewer();
                }
                ExportResultsAction<DomainObject, Reference> action = new ExportResultsAction<>(exportSearchResults, viewer);
                action.actionPerformed(null);
            }

            @Override
            protected void hadError(Throwable error) {
                FrameworkAccess.handleException(error);
            }
        };

        worker.setProgressMonitor(new IndeterminateProgressMonitor(FrameworkAccess.getMainFrame(), "Loading...", ""));
        worker.execute();
    }

    @Override
    public DomainObjectSelectionModel getSelectionModel() {
        return selectionModel;
    }

    @Override
    public DomainObjectEditSelectionModel getEditSelectionModel() {
        return editSelectionModel;
    }

    @Override
    public ViewerContext<DomainObject, Reference> getViewerContext() {
        return new ViewerContext<DomainObject, Reference>() {
            @Override
            public ChildSelectionModel<DomainObject, Reference> getSelectionModel() {
                return selectionModel;
            }

            @Override
            public ChildSelectionModel<DomainObject, Reference> getEditSelectionModel() {
                return resultsPanel.isEditMode() ? editSelectionModel : null;
            }

            @Override
            public ImageModel<DomainObject, Reference> getViewerModel() {
                return resultsPanel.getImageModel();
            }
        };
    }

    @Override
    public Long getCurrentContextId() {
        Object parentObject = getSelectionModel().getParentObject();
        if (parentObject instanceof HasIdentifier) {
            return ((HasIdentifier)parentObject).getId();
        }
        throw new IllegalStateException("Parent object has no identifier: "+getSelectionModel().getParentObject());
    }
}
