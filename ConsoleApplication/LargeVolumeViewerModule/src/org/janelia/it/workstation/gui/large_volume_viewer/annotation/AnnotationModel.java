package org.janelia.it.workstation.gui.large_volume_viewer.annotation;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.janelia.console.viewerapi.model.NeuronSet;
import org.janelia.it.jacs.integration.FrameworkImplProvider;
import org.janelia.it.jacs.model.domain.support.DomainUtils;
import org.janelia.it.jacs.model.domain.tiledMicroscope.BulkNeuronStyleUpdate;
import org.janelia.it.jacs.model.domain.tiledMicroscope.TmAnchoredPath;
import org.janelia.it.jacs.model.domain.tiledMicroscope.TmAnchoredPathEndpoints;
import org.janelia.it.jacs.model.domain.tiledMicroscope.TmGeoAnnotation;
import org.janelia.it.jacs.model.domain.tiledMicroscope.TmNeuronMetadata;
import org.janelia.it.jacs.model.domain.tiledMicroscope.TmSample;
import org.janelia.it.jacs.model.domain.tiledMicroscope.TmStructuredTextAnnotation;
import org.janelia.it.jacs.model.domain.tiledMicroscope.TmWorkspace;
import org.janelia.it.jacs.model.user_data.tiledMicroscope.TmNeuronTagMap;
import org.janelia.it.jacs.model.user_data.tiled_microscope_builder.TmModelManipulator;
import org.janelia.it.jacs.model.util.MatrixUtilities;
import org.janelia.it.jacs.shared.geom.ParametrizedLine;
import org.janelia.it.jacs.shared.geom.Vec3;
import org.janelia.it.jacs.shared.swc.SWCData;
import org.janelia.it.jacs.shared.swc.SWCDataConverter;
import org.janelia.it.jacs.shared.swc.SWCNode;
import org.janelia.it.jacs.shared.utils.Progress;
import org.janelia.it.workstation.browser.ConsoleApp;
import org.janelia.it.workstation.browser.events.selection.DomainObjectSelectionModel;
import org.janelia.it.workstation.browser.events.selection.DomainObjectSelectionSupport;
import org.janelia.it.workstation.browser.workers.IndeterminateProgressMonitor;
import org.janelia.it.workstation.browser.workers.SimpleWorker;
import org.janelia.it.workstation.gui.large_volume_viewer.LoadTimer;
import org.janelia.it.workstation.gui.large_volume_viewer.activity_logging.ActivityLogHelper;
import org.janelia.it.workstation.gui.large_volume_viewer.api.ModelTranslation;
import org.janelia.it.workstation.gui.large_volume_viewer.api.TiledMicroscopeDomainMgr;
import org.janelia.it.workstation.gui.large_volume_viewer.controller.GlobalAnnotationListener;
import org.janelia.it.workstation.gui.large_volume_viewer.controller.NotesUpdateListener;
import org.janelia.it.workstation.gui.large_volume_viewer.controller.SkeletonController;
import org.janelia.it.workstation.gui.large_volume_viewer.controller.TmAnchoredPathListener;
import org.janelia.it.workstation.gui.large_volume_viewer.controller.TmGeoAnnotationModListener;
import org.janelia.it.workstation.gui.large_volume_viewer.controller.ViewStateListener;
import org.janelia.it.workstation.gui.large_volume_viewer.model_adapter.DomainMgrTmModelAdapter;
import org.janelia.it.workstation.gui.large_volume_viewer.neuron_api.NeuronSetAdapter;
import org.janelia.it.workstation.gui.large_volume_viewer.style.NeuronStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Stopwatch;

import Jama.Matrix;

public class AnnotationModel implements DomainObjectSelectionSupport
/*

this class is responsible for handling requests from the AnnotationManager.  those
requests are ready-to-execute; the AnnotationManager has taken care of validation.

public methods in this class that throw exceptions are the ones that involve db calls, and
they should all be called from worker threads.  the others, typically getters of
various info, do not.  private methods don't necessarily follow that pattern.

a note on entities: as of the early 2016 update, we act almost exclusively
on domain objects and then persist them rather than calling through a DAO
to make changes on entities.  be careful that the workspace and neuron objects
are kept up to date locally!

this class does not interact directly with the UI.  it observes
UI elements that select, and its events are connected with a variety of UI
elements that need to respond to changing data.  this sometimes makes it hard
to know whether methods are called in the Java EDT (event thread) or not, and
that's important for knowing how you have to call the UI updates.  the answer
is that all the calls that hit the db go through TiledMicroscopeDomainMgr and could throw
exceptions.  so any call that calls TiledMicroscopeDomainMgr must catch Exceptions, and
therefore it should do its updates on the EDT, because it's probably being
called from a  SimpleWorker thread.
*/
{
    public static final String STD_SWC_EXTENSION = SWCData.STD_SWC_EXTENSION;
    private static final String COLOR_FORMAT = "# COLOR %f,%f,%f";
    private static final String NAME_FORMAT = "# NAME %s";

    private final TiledMicroscopeDomainMgr tmDomainMgr;

    private SWCDataConverter swcDataConverter;
    private final DomainMgrTmModelAdapter modelAdapter;

    private TmSample currentSample;
    private TmWorkspace currentWorkspace;
    private TmNeuronMetadata currentNeuron;
    private TmNeuronTagMap currentTagMap;

    private ViewStateListener viewStateListener;
    private NotesUpdateListener notesUpdateListener;

    private final FilteredAnnotationModel filteredAnnotationModel;
    private final NeuronSetAdapter neuronSetAdapter; // For communicating annotations to Horta

    private final Collection<TmGeoAnnotationModListener> tmGeoAnnoModListeners = new ArrayList<>();
    private final Collection<TmAnchoredPathListener> tmAnchoredPathListeners = new ArrayList<>();
    private final Collection<GlobalAnnotationListener> globalAnnotationListeners = new ArrayList<>();

    private final TmModelManipulator neuronManager;

    private LoadTimer addTimer = new LoadTimer();

    private final ActivityLogHelper activityLog = ActivityLogHelper.getInstance();
    private static final Logger log = LoggerFactory.getLogger(AnnotationModel.class);

    private final DomainObjectSelectionModel selectionModel = new DomainObjectSelectionModel();
    

    // ----- constants
    // how far away to try to put split anchors (pixels)
    private static final Double SPLIT_ANCHOR_DISTANCE = 60.0;


    public AnnotationModel() {
        log.info("Creating new AnnotationModel {}", this);
        this.tmDomainMgr = TiledMicroscopeDomainMgr.getDomainMgr();
        this.modelAdapter = new DomainMgrTmModelAdapter();
        this.neuronManager = new TmModelManipulator(modelAdapter);
        this.filteredAnnotationModel = new FilteredAnnotationModel();
        this.neuronSetAdapter = new NeuronSetAdapter();
        neuronSetAdapter.observe(this);
        
        // Report performance statistics when program closes
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                addTimer.report();
            }
        });
    }

    @Override
    public DomainObjectSelectionModel getSelectionModel() {
        return selectionModel;
    }
    
    public Collection<TmNeuronMetadata> getNeuronList() {
        return neuronManager.getNeurons();
    }

    public void addNeuron(TmNeuronMetadata neuron) {
        neuronManager.addNeuron(neuron);
    }
    
    public FilteredAnnotationModel getFilteredAnnotationModel() {
        return filteredAnnotationModel;
    }

    public void addTmGeoAnnotationModListener(TmGeoAnnotationModListener listener) {
        tmGeoAnnoModListeners.add(listener);
    }

    public void removeTmGeoAnnotationModListener(TmGeoAnnotationModListener listener) {
        tmGeoAnnoModListeners.remove(listener);
    }

    public void addTmAnchoredPathListener(TmAnchoredPathListener listener) {
        tmAnchoredPathListeners.add(listener);
    }

    public void removeTmAnchoredPathListener(TmAnchoredPathListener listener) {
        tmAnchoredPathListeners.remove(listener);
    }

    public void addGlobalAnnotationListener(GlobalAnnotationListener listener) {
        globalAnnotationListeners.add(listener);
    }

    public void removeGlobalAnnotationListener(GlobalAnnotationListener listener) {
        globalAnnotationListeners.remove(listener);
    }

    public void setViewStateListener(ViewStateListener listener) {
        this.viewStateListener = listener;
    }

    public void setNotesUpdateListener(NotesUpdateListener notesUpdateListener) {
        this.notesUpdateListener = notesUpdateListener;
    }

    public TmWorkspace getCurrentWorkspace() {
        return currentWorkspace;
    }
    
    public void saveCurrentWorkspace() throws Exception {
        tmDomainMgr.save(currentWorkspace);
    }

    public TmSample getCurrentSample() {
        return currentSample;
    }

    public void setSampleMatrices(Matrix micronToVoxMatrix, Matrix voxToMicronMatrix) throws Exception {
        if (currentSample==null) {
            throw new IllegalStateException("Sample is not loaded");
        }
        currentSample.setMicronToVoxMatrix(MatrixUtilities.serializeMatrix(micronToVoxMatrix, "micronToVoxMatrix"));
        currentSample.setVoxToMicronMatrix(MatrixUtilities.serializeMatrix(voxToMicronMatrix, "voxToMicronMatrix"));
        tmDomainMgr.save(currentSample);
    }

    private Long getWsId() {
        if (currentWorkspace != null) {
            return currentWorkspace.getId();
        }
        else {
            return -1L;
        }
    }

    public synchronized void clear() {
        log.info("Clearing annotation model");
        currentWorkspace = null;
        currentSample = null;
        currentTagMap = null;
        setCurrentNeuron(null);
    }
    
    public synchronized void loadSample(final TmSample sample) throws Exception {
        if (sample == null) {
            throw new IllegalArgumentException("Cannot load null sample");
        }
        
        log.info("Loading sample {}", sample.getId());
        currentWorkspace = null;
        currentSample = sample;
        currentTagMap = null;
    }
    
    public synchronized void loadWorkspace(final TmWorkspace workspace) throws Exception {
        if (workspace == null) {
            throw new IllegalArgumentException("Cannot load null workspace");
        }

        log.info("Loading workspace {}", workspace.getId());
        currentWorkspace = workspace;
        currentSample = tmDomainMgr.getSample(workspace);

        // Neurons need to be loaded en masse from raw data from server.
        log.info("Loading neurons for workspace {}", workspace.getId());
        neuronManager.loadWorkspaceNeurons(workspace);

        // Create the local tag map for cached access to tags
        log.info("Creating tag map for workspace {}", workspace.getId());
        currentTagMap = new TmNeuronTagMap();
        for(TmNeuronMetadata tmNeuronMetadata : neuronManager.getNeurons()) {
            for(String tag : tmNeuronMetadata.getTags()) {
                currentTagMap.addTag(tag, tmNeuronMetadata);
            }
        }
        
        // Clear neuron selection
        log.info("Clearing current neuron for workspace {}", workspace.getId());
        setCurrentNeuron(null);        
    }
    
    public void loadComplete() { 
        final TmWorkspace updateWorkspace = getCurrentWorkspace();
        fireWorkspaceLoaded(updateWorkspace);
        fireNeuronSelected(null);
        if (updateWorkspace!=null) {
            activityLog.logLoadWorkspace(updateWorkspace.getId());
        }
    }

    public void setSWCDataConverter( SWCDataConverter converter ) {
        this.swcDataConverter = converter;
    }

    // current neuron methods
    public TmNeuronMetadata getCurrentNeuron() {
        log.trace("getCurrentNeuron = {}",currentNeuron);
        return currentNeuron;
    }

    // this method sets the current neuron but does not
    //  fire an event to update the UI
    private synchronized void setCurrentNeuron(TmNeuronMetadata neuron) {
        log.trace("setCurrentNeuron({})",neuron);
        // be sure we're using the neuron object from the current workspace
        if (neuron != null) {
            currentNeuron = getNeuronFromNeuronID(neuron.getId());
        } else {
            currentNeuron = null;
        }
    }

    // this method sets the current neuron *and*
    //  updates the UI; null neuron means deselect
    public void selectNeuron(TmNeuronMetadata neuron) {
        log.info("selectNeuron({})",neuron);
        if (neuron != null && getCurrentNeuron() != null && neuron.getId().equals(getCurrentNeuron().getId())) {
            return;
        }
        setCurrentNeuron(neuron); // synchronized on this AnnotationModel here
        fireNeuronSelected(neuron);
        if (getCurrentWorkspace()!=null && neuron!=null) {
            activityLog.logSelectNeuron(getCurrentWorkspace().getId(), neuron.getId());
        }
    }

    // convenience methods
    /**
     * given the ID of an annotation, return an object wrapping it (or null)
     */
    public TmGeoAnnotation getGeoAnnotationFromID(Long annotationID) {
        if (getCurrentWorkspace() == null) {
            return null;
        }

        TmNeuronMetadata foundNeuron = getNeuronFromAnnotationID(annotationID);
        if (foundNeuron != null) {
            return foundNeuron.getGeoAnnotationMap().get(annotationID);
        } else {
            return null;
        }
    }

    /**
     * given the ID of an annotation, return the neuron that contains it (or null)
     */
    public TmNeuronMetadata getNeuronFromAnnotationID(Long annotationID) {
        if (getCurrentWorkspace() == null) {
            return null;
        }

        TmNeuronMetadata foundNeuron = null;
        for (TmNeuronMetadata neuron : getNeuronList()) {
            if (neuron.getGeoAnnotationMap().containsKey(annotationID)) {
                foundNeuron = neuron;
                break;
            }
        }
        
        if (foundNeuron==null) {
            log.warn("There is no neuron with annotation: ", annotationID);

            // I don't have time to fix this now. But this "throw" breaks adding annotations in Horta
            // throw new IllegalStateException("There is no neuron with annotation: "+annotationID);
        }
        // Perhaps an actual assert could be useful instead?
        // assert(foundNeuron != null);
        
        log.debug("getNeuronFromAnnotationID({}) = {}",annotationID, foundNeuron);
        return foundNeuron;
    }
    
    public TmNeuronMetadata getNeuronFromNeuronID(Long neuronID) {
        TmNeuronMetadata foundNeuron = neuronManager.getNeuronById(neuronID);
        if (foundNeuron==null) {
            log.warn("There is no neuron with id: ", neuronID);
        }
        log.debug("getNeuronFromNeuronID({}) = {}",neuronID,foundNeuron);
        return foundNeuron;
    }

    /**
     * given an annotation, find its ultimate parent, which is the root of
     * its neurite
     */
    public TmGeoAnnotation getNeuriteRootAnnotation(TmGeoAnnotation annotation) {
        if (annotation == null) {
            return annotation;
        }

        TmNeuronMetadata neuron = getNeuronFromAnnotationID(annotation.getId());        
        TmGeoAnnotation current = annotation;
        TmGeoAnnotation parent = neuron.getParentOf(current);
        while (parent !=null) {
            current = parent;
            parent = neuron.getParentOf(current);
        }
        return current;
    }

    /**
     * find the annotation closest to the input location, excluding
     * the input annotation (null = don't exclude any)
     */
    public TmGeoAnnotation getClosestAnnotation(Vec3 location, TmGeoAnnotation excludedAnnotation) {

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();

        TmGeoAnnotation closest = null;
        // I hate magic numbers as much as the next programmer, but surely this
        //  is big enough, right?
        double closestSquaredDist = 1.0e99;

        double dx, dy, dz, dist;

        // our valid IDs are positive, so this will never match
        Long excludedAnnotationID;
        if (excludedAnnotation == null) {
            excludedAnnotationID = -1L;
        } else {
            excludedAnnotationID = excludedAnnotation.getId();
        }
        for (TmNeuronMetadata neuron: getNeuronList()) {
            for (TmGeoAnnotation root: neuron.getRootAnnotations()) {
                for (TmGeoAnnotation ann: neuron.getSubTreeList(root)) {
                    if (excludedAnnotationID.equals(ann.getId())) {
                        continue;
                    }
                    dx = ann.getX() - x;
                    dy = ann.getY() - y;
                    dz = ann.getZ() - z;
                    dist = dx * dx + dy * dy + dz * dz;
                    if (dist < closestSquaredDist) {
                        closestSquaredDist = dist;
                        closest = ann;
                    }
                }
            }
        }
        return closest;
    }

    /**
     * create a neuron in the current workspace
     *
     * @param name = name of neuron
     * @throws Exception
     */
    public synchronized TmNeuronMetadata createNeuron(String name) throws Exception {
        final TmNeuronMetadata neuron = neuronManager.createTiledMicroscopeNeuron(currentWorkspace, name);

        // Update local workspace
        log.info("Neuron was created: "+neuron);
        setCurrentNeuron(neuron);
        
        final TmWorkspace workspace = getCurrentWorkspace();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireWorkspaceLoaded(workspace);
                fireNeuronSelected(neuron);
                fireWorkspaceChanged();
                activityLog.logCreateNeuron(workspace.getId(), neuron.getId());
            }
        });
        
        return neuron;
    }

    /**
     * rename the given neuron
     */
    public synchronized void renameCurrentNeuron(String name) throws Exception {
        // rename whatever neuron was current at time of start of this call.
        final TmNeuronMetadata operationCurrentNeuron = getCurrentNeuron();
        operationCurrentNeuron.setName(name);
        this.neuronManager.saveNeuronData(operationCurrentNeuron);

        Long currentNeuronID = operationCurrentNeuron.getId();

        // update & notify
        final TmNeuronMetadata neuron = getNeuronFromNeuronID(currentNeuronID);
        neuron.setName(name);

        final TmWorkspace workspace = getCurrentWorkspace();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireWorkspaceLoaded(workspace);
                fireNeuronSelected(neuron);
                activityLog.logRenameNeuron(workspace.getId(), neuron.getId());
            }
        });
    }

    public synchronized void deleteCurrentNeuron() throws Exception {
        if (getCurrentNeuron() == null) {
            return;
        } else {
            deleteNeuron(getCurrentNeuron());
            setCurrentNeuron(null);
        }
    }

    public synchronized void deleteNeuron(final TmNeuronMetadata deletedNeuron) throws Exception {

        final boolean hadAnnotations = deletedNeuron.getGeoAnnotationMap().size() > 0;

        // delete
        neuronManager.deleteNeuron(currentWorkspace, deletedNeuron);
        log.info("Neuron was deleted: "+deletedNeuron);

        // updates
        final TmWorkspace workspace = getCurrentWorkspace();

        final ArrayList<TmGeoAnnotation> tempAnnotationList = new ArrayList<>(deletedNeuron.getGeoAnnotationMap().values());
        final ArrayList<TmAnchoredPath> tempPathList = new ArrayList<>(deletedNeuron.getAnchoredPathMap().values());
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {

                final SkeletonController skeletonController=SkeletonController.getInstance();
                skeletonController.setSkipSkeletonChange(true);

                final FilteredAnnotationList filteredAnnotationList=FilteredAnnotationList.getInstance();
                filteredAnnotationList.setSkipUpdate(true);

                fireNeuronSelected(null);

                SimpleWorker worker = new SimpleWorker() {
                    @Override
                    protected void doStuff() throws Exception {
                        // This takes a long time, and doesn't need to be run on the EDT because updates are disabled
                        if (hadAnnotations) {
                            fireAnnotationsDeleted(tempAnnotationList);
                            fireAnchoredPathsRemoved(tempPathList);
                        }
                    }

                    @Override
                    protected void hadSuccess() {
                        fireWorkspaceLoaded(workspace);
                        fireWorkspaceChanged();

                        skeletonController.setSkipSkeletonChange(false);
                        skeletonController.skeletonChanged();

                        filteredAnnotationList.setSkipUpdate(false);
                        if (hadAnnotations) {
                            filteredAnnotationList.updateData();
                        }
                        activityLog.logDeleteNeuron(workspace.getId(), deletedNeuron.getId());
                    }

                    @Override
                    protected void hadError(Throwable error) {
                        ConsoleApp.handleException(error);
                    }
                };
                worker.setProgressMonitor(new IndeterminateProgressMonitor(FrameworkImplProvider.getMainFrame(), "Deleting neuron...", "", false));
                worker.execute();
            }
        });
    }

    /**
     * Create a new workspace for the given sample, owned by the current user.
     * @param sampleId = tiled microscope sample ID
     * @param name = name of new workspace
     * @throws Exception
     */
    public synchronized TmWorkspace createWorkspace(Long sampleId, String name) throws Exception {
        TmWorkspace workspace = tmDomainMgr.createWorkspace(sampleId, name);
        activityLog.logCreateWorkspace(workspace.getId());
        return workspace;
    }

    /**
     * Create a new copy of the given workspace, owned by the current user.
     * @param sampleId = workspace Id
     * @param name = name of new workspace
     * @throws Exception
     */
    public synchronized TmWorkspace copyWorkspace(TmWorkspace workspace, String name) throws Exception {
        TmWorkspace workspaceCopy = tmDomainMgr.copyWorkspace(workspace, name);
        activityLog.logCreateWorkspace(workspace.getId());
        return workspaceCopy;
    }
    
    /**
     * add a root annotation to the given neuron; this is an annotation without a parent
     *
     * @param neuron = neuron object
     * @param xyz = x, y, z location of new annotation
     * @throws Exception
     */
    public synchronized TmGeoAnnotation addRootAnnotation(final TmNeuronMetadata neuron, final Vec3 xyz) throws Exception {
        // the null  in this call means "this is a root annotation" (would otherwise
        //  be the parent).  Updates to neuron's collections are done in the
        //  as well.
        final TmGeoAnnotation annotation = neuronManager.addGeometricAnnotation(
                neuron, neuron.getId(), xyz.x(), xyz.y(), xyz.z());

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireNeuronSelected(neuron);
                fireAnnotationAdded(annotation);
                activityLog.logEndOfOperation(getWsId(), xyz);
            }
        });

        return annotation;
    }

    /**
     * add a child annotation
     *
     * @param parentAnn = parent annotation object
     * @param xyz = location of new child annotation
     * @throws Exception
     */
    public synchronized TmGeoAnnotation addChildAnnotation(TmGeoAnnotation parentAnn, final Vec3 xyz) throws Exception {
        if (parentAnn == null) {
            return null;
        }

        addTimer.mark("start addChildAnn");
                 
        final TmNeuronMetadata neuron = getNeuronFromNeuronID(parentAnn.getNeuronId());
                
        final TmGeoAnnotation annotation = neuronManager.addGeometricAnnotation(
                neuron, parentAnn.getId(), xyz.x(), xyz.y(), xyz.z());
        
        // the parent may lose some predefined notes (finished end, possible branch)
        stripPredefNotes(neuron, parentAnn.getId());
        
        if (automatedTracingEnabled()) {
            if (viewStateListener != null)
                viewStateListener.pathTraceRequested(annotation.getId());
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireNeuronSelected(neuron);
                fireAnnotationAdded(annotation);
                activityLog.logEndOfOperation(getWsId(), xyz);
            }
        });
        
        addTimer.mark("end addChildAnn");
        // reset timer state; we don't care about end > start
        addTimer.clearPreviousStepName();

        return annotation;
    }

    /**
     * move an existing annotation to a new location
     *
     * @param annotationID = ID of annotation to move
     * @param location = new location
     * @throws Exception
     */
    public synchronized void moveAnnotation(final Long annotationID, final Vec3 location) throws Exception {
        final TmNeuronMetadata neuron = this.getNeuronFromAnnotationID(annotationID);
        TmGeoAnnotation annotation = getGeoAnnotationFromID(neuron, annotationID);

        // find each connecting annotation; if there's a traced path to it,
        //  remove it (refresh annotation!)
        // at the same time, delete the paths out of the local neuron object, too
        TmGeoAnnotation parent = neuron.getParentOf(annotation);
        if (parent != null) {
            removeAnchoredPath(neuron, annotation, parent);
            neuron.getAnchoredPathMap().remove(new TmAnchoredPathEndpoints(annotation.getId(), parent.getId()));
        }
        for (TmGeoAnnotation neighbor: neuron.getChildrenOf(annotation)) {
            removeAnchoredPath(neuron, annotation, neighbor);
            neuron.getAnchoredPathMap().remove(new TmAnchoredPathEndpoints(annotation.getId(), neighbor.getId()));
        }

        // update local annotation object
        synchronized(annotation) {
            annotation.setX(location.getX());
            annotation.setY(location.getY());
            annotation.setZ(location.getZ());
        }

        try {
            // Update value in database.
            synchronized(neuron) {
                neuronManager.saveNeuronData(neuron);
            }
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    fireAnnotationMoved(annotationID);
                    activityLog.logEndOfOperation(getWsId(), location);
                }
            });
        } catch (Exception e) {
            // error means not persisted; however, in the process of moving,
            //  the marker's already been moved, to give interactive feedback
            //  to the user; so in case of error, tell the view to update to current
            //  position (pre-move)
            // this is unfortunately untested, because I couldn't think of an
            //  easy way to simulate or force a failure!
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    fireAnnotationNotMoved(annotationID);
                    activityLog.logEndOfOperation(getWsId(), location);
                }
            });
            throw e;
        }

        final TmWorkspace workspace = getCurrentWorkspace();

        if (automatedTracingEnabled()) {
            // trace to parent, and each child to this parent:
            viewStateListener.pathTraceRequested(annotation.getId());
            for (TmGeoAnnotation child : neuron.getChildrenOf(annotation)) {
                viewStateListener.pathTraceRequested(child.getId());
            }
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireNotesUpdated(workspace);

                if (getCurrentNeuron() != null) {
                    if (neuron.getId().equals(getCurrentNeuron().getId())) {
                        fireNeuronSelected(getCurrentNeuron());
                    }
                }
                activityLog.logEndOfOperation(getWsId(), location);
            }
        });
    }

    /**
     * change radius of an existing annotation
     *
     * @param annotationID = ID of annotation to move
     * @param radius = new radius, in units of micrometers
     * @throws Exception
     */
    public synchronized void updateAnnotationRadius(final Long annotationID, final float radius) throws Exception {
        final TmNeuronMetadata neuron = this.getNeuronFromAnnotationID(annotationID);
        final TmGeoAnnotation annotation = getGeoAnnotationFromID(neuron, annotationID);

        // update local annotation object
        final Double oldRadius = annotation.getRadius();
        synchronized(annotation) {
            annotation.setRadius(new Double(radius));
        }

        try {
            // Update value in database.
            synchronized(neuron) {
                neuronManager.saveNeuronData(neuron);
                fireAnnotationRadiusUpdated(annotationID);
            }
        } catch (Exception e) {
            // error means not persisted; however, in the process of moving,
            //  the marker's already been moved, to give interactive feedback
            //  to the user; so in case of error, tell the view to update to current
            //  position (pre-move)
            // this is unfortunately untested, because I couldn't think of an
            //  easy way to simulate or force a failure!
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    annotation.setRadius(oldRadius);
                    // activityLog.logEndOfOperation(getWsId(), location);
                }
            });
            throw e;
        }

        final TmWorkspace workspace = getCurrentWorkspace();

        if (automatedTracingEnabled()) {
            // trace to parent, and each child to this parent:
            viewStateListener.pathTraceRequested(annotation.getId());
            for (TmGeoAnnotation child : neuron.getChildrenOf(annotation)) {
                viewStateListener.pathTraceRequested(child.getId());
            }
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireNotesUpdated(workspace);

                if (getCurrentNeuron() != null) {
                    if (neuron.getId().equals(getCurrentNeuron().getId())) {
                        fireNeuronSelected(getCurrentNeuron());
                    }
                }
                // activityLog.logEndOfOperation(getWsId(), location);
            }
        });
    }

    /**
     * merge the neurite that has source Annotation into the neurite containing
     * targetAnnotation
     */
    public synchronized void mergeNeurite(final Long sourceAnnotationID, final Long targetAnnotationID) throws Exception {

        // temporary logging for Jayaram:
        // log.info("beginning mergeNeurite()");
        Stopwatch stopwatch = new Stopwatch();
        stopwatch.start();

        TmNeuronMetadata targetNeuron = getNeuronFromAnnotationID(targetAnnotationID);
        final TmGeoAnnotation targetAnnotation = targetNeuron.getGeoAnnotationMap().get(targetAnnotationID);
        TmGeoAnnotation sourceAnnotation = getGeoAnnotationFromID(sourceAnnotationID);

        TmNeuronMetadata sourceNeuron = null;
        final TmWorkspace workspace = getCurrentWorkspace();
        if (!sourceAnnotation.getNeuronId().equals(targetAnnotation.getNeuronId())) {
            sourceNeuron = getNeuronFromAnnotationID(sourceAnnotationID);
        }
        else {
            sourceNeuron = targetNeuron;
        }

        // reroot source neurite to source ann
        if (!sourceAnnotation.isRoot()) {
            // log.info("Handling non-root case.");
            neuronManager.rerootNeurite(sourceNeuron, sourceAnnotation);
        }

        // if source neurite not in same neuron as dest neurite: move it; don't
        //  use annModel.moveNeurite() because we don't want those updates & signals yet
        if (!sourceNeuron.getId().equals(targetNeuron.getId())) {
            // log.info("Two different neurons.");
            neuronManager.moveNeurite(sourceAnnotation, sourceNeuron, targetNeuron);
        }


        // reparent source annotation to dest annotation:
        // log.info("Reparenting annotations.");
        neuronManager.reparentGeometricAnnotation(sourceAnnotation, targetAnnotationID, targetNeuron);


        // Establish p/c linkage between target and source.
        // log.info("Parent/child linkages target and source.");
        sourceAnnotation.setParentId(targetAnnotationID);

        final TmNeuronMetadata updateTargetNeuron = getNeuronFromAnnotationID(targetAnnotationID);
        setCurrentNeuron(updateTargetNeuron);

        // trace new path:
        if (automatedTracingEnabled()) {
            // log.info("Tracing paths.");
            viewStateListener.pathTraceRequested(sourceAnnotationID);
        }

        // see note in addChildAnnotations re: predef notes
        // for merge, two linked annotations are affected; fortunately, the
        //  neuron has just been refreshed
        // log.info("Stripping predef notes.");
        stripPredefNotes(updateTargetNeuron, targetAnnotationID);
        stripPredefNotes(updateTargetNeuron, sourceAnnotationID);

        // Save the target neuron.
        // log.info("Saving target neuron.");
        neuronManager.removeNeuron(targetNeuron);
        neuronManager.saveNeuronData(updateTargetNeuron);
        neuronManager.addNeuron(updateTargetNeuron);
        // Save the source neuron, empty or not.
        if (! sourceNeuron.getId().equals(updateTargetNeuron.getId())) {
            // log.info("Saving source neuron.");
            neuronManager.saveNeuronData(sourceNeuron);
        }

        // if source neuron is now empty, delete it
        if (sourceNeuron.getGeoAnnotationMap().size() == 0) {
            deleteNeuron(sourceNeuron);
        }

        final TmGeoAnnotation updateSourceAnnotation = getGeoAnnotationFromID(sourceAnnotationID);
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                // log.info("beginning UI update for mergeNeurite()");
                Stopwatch stopwatch = new Stopwatch();
                stopwatch.start();
                fireNotesUpdated(workspace);
                fireNeuronSelected(updateTargetNeuron);
                fireWorkspaceLoaded(workspace);
                fireAnnotationReparented(updateSourceAnnotation);
                // log.info("ending UI update for mergeNeurite(); elapsed = " + stopwatch);
                activityLog.logEndOfOperation(getWsId(), targetAnnotation);
                stopwatch.stop();
            }
        });

        // log.info("ending mergeNeurite(); elapsed = " + stopwatch);
        stopwatch.stop();
    }


    /**
     * move the neurite containing the input annotation to the given neuron
     */
    public synchronized void moveNeurite(final TmGeoAnnotation annotation, TmNeuronMetadata destNeuron) throws Exception {
        if (eitherIsNull(annotation, destNeuron)) {
            return;
        }
        TmNeuronMetadata sourceNeuron = getNeuronFromNeuronID(annotation.getNeuronId());
        neuronManager.moveNeurite(annotation, sourceNeuron, destNeuron);
        neuronManager.saveNeuronData(sourceNeuron);
        neuronManager.saveNeuronData(destNeuron);

        final TmWorkspace workspace = getCurrentWorkspace();
        final TmNeuronMetadata currentNeuron = getCurrentNeuron();

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireNotesUpdated(workspace);
                fireNeuronSelected(currentNeuron);
                fireWorkspaceLoaded(workspace);
                activityLog.logEndOfOperation(getWsId(), annotation);
            }
        });

    }


    /**
     * this method deletes a link, which is defined as an annotation with
     * one parent and no more than one child (not a root, not a branch point)
     * (unless it's a root with no children)
     *
     * @param link = annotation object
     * @throws Exception
     */
    public synchronized void deleteLink(final TmGeoAnnotation link) throws Exception {
        if (link == null) {
            return;
        }

        // check it's not a branch or a root with children
        if (link.getChildIds().size() > 1) {
            return;
        }

        if (link.isRoot() && link.getChildIds().size() > 0) {
            return;
        }

        // check we can find it
        // again, not clear if this should be an exception or not?
        TmNeuronMetadata neuron = getNeuronFromAnnotationID(link.getId());
        if (neuron == null) {
            // should this be an error?  it's a sign that the annotation has already
            //  been deleted, or something else that shouldn't happen
            log.info("Unexpected null neuron during anchor deletion");
            return;
        }
        if (neuron.getGeoAnnotationMap() == null) {
            log.info("Unexpected null neuron.getGeoAnnotationMap() during anchor deletion");
        }

        // begin the (long) deletion process
        // reparent the deleted node's child (if there is one) to the node's parent
        TmGeoAnnotation parent = neuron.getParentOf(link);
        TmGeoAnnotation child = null;
        if (link.getChildIds().size() == 1) {
            child = neuron.getChildrenOf(link).get(0);
            {
                // Amina saw a NullPointerException inside the reparentGeometricAnnotation call below.
                // Perhaps logging the trouble could help here
                if (child == null) {
                    log.info("Unexpected null child during anchor deletion");
                    return;
                }
            }
            neuronManager.reparentGeometricAnnotation(child, parent.getId(), neuron);

            // if segment to child had a traced path, remove it
            removeAnchoredPath(neuron, link, child);
        }

        // if segment to parent had a trace, remove it
        removeAnchoredPath(neuron, link, parent);

        // if the link had a note, delete it:
        if (neuron.getStructuredTextAnnotationMap().containsKey(link.getId())) {
            neuron.getStructuredTextAnnotationMap().remove(link.getId());
        }

        // if link had a child, remove link
        if (child != null) {
            link.getChildIds().remove(child.getId());
        }

        // remove link from its parent
        if (!link.isRoot()) {
            parent.getChildIds().remove(link.getId());
        }

        // ...and finally get rid of the link itself; then, we're done, and
        //  the neuron can be serialized
        neuron.getGeoAnnotationMap().remove(link.getId());
        if (link.isRoot()) {
            neuron.removeRootAnnotation(link);
        }
        neuronManager.saveNeuronData(neuron);

        final TmWorkspace workspace = getCurrentWorkspace();

        final TmGeoAnnotation updateChild;
        if (child != null) {
            neuron = getNeuronFromAnnotationID(child.getId());
            updateChild = neuron.getGeoAnnotationMap().get(child.getId());
        } else {
            updateChild = null;
        }

        // if we're tracing, retrace if there's a new connection
        if (automatedTracingEnabled() && child != null) {
            viewStateListener.pathTraceRequested(child.getId());
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireNotesUpdated(workspace);

                // need to delete an anchor (which does undraw it); but then
                //  need to redraw the neurite, because we need the link
                //  from the reparenting to appear
                List<TmGeoAnnotation> deleteList = new ArrayList<>(1);
                deleteList.add(link);
                fireAnnotationsDeleted(deleteList);

                if (updateChild != null) {
                    fireAnnotationReparented(updateChild);
                }
                activityLog.logEndOfOperation(getWsId(), link);

            }
        });
    }

    /**
     * this method deletes an annotation and all of its children, and its
     * children's children, yea, unto every generation that liveth
     *
     * @param rootAnnotation = annotation to be deleted along with its descendents
     * @throws Exception
     */
    public synchronized void deleteSubTree(final TmGeoAnnotation rootAnnotation) throws Exception {
        if (rootAnnotation == null) {
            return;
        }

        TmNeuronMetadata neuron = getNeuronFromAnnotationID(rootAnnotation.getId());
        if (neuron == null) {
            // should this be an error?  it's a sign that the annotation has already
            //  been deleted, or something else that shouldn't happen
            return;
        }

        // grab the parent of the root before the root disappears:
        TmGeoAnnotation rootParent = neuron.getParentOf(rootAnnotation);

        final List<TmGeoAnnotation> deleteList = neuron.getSubTreeList(rootAnnotation);
        TmStructuredTextAnnotation note;
        for (TmGeoAnnotation annotation: deleteList) {
            // for each annotation, delete any paths traced to its children;
            //  do before the deletion!
            for (TmGeoAnnotation child: neuron.getChildrenOf(annotation)) {
                removeAnchoredPath(neuron, annotation, child);
            }

            note = neuron.getStructuredTextAnnotationMap().get(annotation.getId());
            if (note != null) {
                // don't use removeNote(); it triggers updates we don't want yet
                neuron.getStructuredTextAnnotationMap().remove(annotation.getId());
            }
            neuron.getGeoAnnotationMap().remove(annotation.getId());
            if (annotation.isRoot()) {
                neuron.removeRootAnnotation(annotation);
            }
        }
        // for the root annotation, also delete any traced paths to the parent,
        // if it exists and eliminate the root annotation as a child of the
        // root-parent.
        if (rootParent != null) {
            removeAnchoredPath(neuron, rootAnnotation, rootParent);
            rootParent.getChildIds().remove(rootAnnotation.getId());
        }

        // Must serialize the neuron, after having made changes.
        neuronManager.saveNeuronData(neuron);

        final TmWorkspace workspace = getCurrentWorkspace();
        final TmNeuronMetadata updateNeuron = getCurrentNeuron();

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireNotesUpdated(workspace);
                fireNeuronSelected(updateNeuron);
                fireAnnotationsDeleted(deleteList);
                activityLog.logEndOfOperation(getWsId(), rootAnnotation);
            }
        });

    }

    /**
     * this method "splits" an annotation; it creates a new annotation on the line
     * between the input annotation and its parent, displaced a bit toward the parent;
     * if it's a root, it inserts in the direction of either its single child, or it's an error
     *
     * @param annotation = annotation to be split
     */
    public synchronized void splitAnnotation(final TmGeoAnnotation annotation) throws Exception {
        if (annotation == null) {
            return;
        }

        // ann1 is the child of ann2 in both cases; if reverse, place the new point
        //  near ann2 instead of ann1
        TmNeuronMetadata neuron = getNeuronFromAnnotationID(annotation.getId());
        TmGeoAnnotation annotation1;
        TmGeoAnnotation annotation2;
        boolean reverse;

        if (annotation.isRoot()) {
            // root case is special; if one child, split toward child; otherwise, error
            //  (with zero or many children, ambiguous where to put new annotation)
            if (annotation.getChildIds().size() != 1) {
                throw new Exception("cannot split root annotation with zero or many children");
            }
            annotation1 = neuron.getChildrenOf(annotation).get(0);
            annotation2 = annotation;
            reverse = true;
        } else {
            // regular point: split toward the parent
            annotation1 = annotation;
            annotation2 = neuron.getParentOf(annotation);
            reverse = false;
        }

        // heuristic: try for a default separation in pixels; we don't want to
        //  go bigger, but we may need to go smaller; if the two anchors are
        //  already really close together, don't go beyond the halfway point
        //  between them
        // (remember, our t=0 is the original point)
        ParametrizedLine pLine = new ParametrizedLine(
                new Vec3(annotation1.getX(), annotation1.getY(), annotation1.getZ()),
                new Vec3(annotation2.getX(), annotation2.getY(), annotation2.getZ())
        );
        Double t = pLine.parameterFromPathLength(SPLIT_ANCHOR_DISTANCE);
        if (t > 0.5) {
            t = 0.5;
        }
        if (reverse) {
            t = 1.0 - t;
        }
        Vec3 newPoint = pLine.getPoint(t);

        // create the new annotation, child of original parent
        final TmGeoAnnotation newAnnotation = neuronManager.addGeometricAnnotation(neuron,
                annotation2.getId(), newPoint.x(), newPoint.y(), newPoint.z());

        //  reparent existing annotation to new annotation
        neuronManager.reparentGeometricAnnotation(annotation1, newAnnotation.getId(), neuron);

        // if that segment had a trace, remove it
        removeAnchoredPath(neuron, annotation1, annotation2);
        neuronManager.saveNeuronData(neuron);

        // retrace
        if (automatedTracingEnabled()) {
            if (viewStateListener != null) {
                viewStateListener.pathTraceRequested(newAnnotation.getId());
                viewStateListener.pathTraceRequested(annotation1.getId());
            }
        }

        final TmGeoAnnotation updateAnnotation = neuron.getGeoAnnotationMap().get(annotation1.getId());
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireAnnotationAdded(newAnnotation);
                fireAnnotationReparented(updateAnnotation);
                activityLog.logEndOfOperation(getWsId(), annotation);
            }
        });
    }

    /**
     * reroot a neurite at the input annotation; it becomes the top level parent, and
     * other annotations' relations are adjusted to compensate
     *
     * @param newRootID = ID of new root annotation for neurite
     * @throws Exception
     */
    public synchronized void rerootNeurite(Long newRootID) throws Exception {
        // do it in the DAO layer
        final TmGeoAnnotation newRoot = getGeoAnnotationFromID(newRootID);
        TmNeuronMetadata neuron = getNeuronFromAnnotationID(newRoot.getId());
        neuronManager.rerootNeurite(neuron, newRoot);

        // see notes in addChildAnnotation re: the predef notes
        // in this case, the new root is the only annotation we need to check
        stripPredefNotes(neuron, newRootID);

        neuronManager.saveNeuronData(neuron);

        if (neuron.getId().equals(getCurrentNeuron().getId())){
            final TmNeuronMetadata updateNeuron = getCurrentNeuron();
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    fireNeuronSelected(updateNeuron);
                    activityLog.logEndOfOperation(getWsId(), newRoot);
                }
            });
        }
    }

    /**
     * split a neurite at the input node; the node is detached from its parent, and it and
     * its children become a new neurite in the same neuron
     *
     * @param newRootID = ID of root of new neurite
     * @throws Exception
     */
    public synchronized void splitNeurite(final Long newRootID) throws Exception {
        final TmGeoAnnotation newRoot = getGeoAnnotationFromID(newRootID);
        TmNeuronMetadata neuron = getNeuronFromAnnotationID(newRootID);
        TmGeoAnnotation newRootParent = neuron.getParentOf(newRoot);
        removeAnchoredPath(neuron, newRoot, newRootParent);
        neuronManager.splitNeurite(neuron, newRoot);

        // update domain objects and database, and notify
        neuronManager.saveNeuronData(neuron);

        final TmNeuronMetadata updateNeuron = getNeuronFromAnnotationID(newRootID);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireAnnotationReparented(updateNeuron.getGeoAnnotationMap().get(newRootID));
                fireNeuronSelected(updateNeuron);
                activityLog.logEndOfOperation(getWsId(), newRoot);
            }
        });
    }

    public synchronized void addAnchoredPath(final TmAnchoredPathEndpoints endpoints, List<List<Integer>> points) throws Exception{

        // check we can find both endpoints in same neuron
        //  don't need to check that they are neighboring; UI gesture already enforces it
        TmNeuronMetadata neuron1 = getNeuronFromAnnotationID(endpoints.getFirstAnnotationID());
        TmNeuronMetadata neuron2 = getNeuronFromAnnotationID(endpoints.getSecondAnnotationID());
        if (eitherIsNull(neuron1, neuron2)) {
            // something's been deleted
            return;
        }
        if (!neuron1.getId().equals(neuron2.getId())) {
            throw new Exception("anchored path annotations are in different neurons");
        }

        // now verify that endpoints for path are still where they were when path
        //  was being drawn (ie, make sure user didn't move the endpoints in the meantime)
        // check that the first and last points in the list match the current locations of the
        //  annotations, in some order (despite stated convention, I have not found the point
        //  list to be in consistent order vis a vis the ordering of the annotation IDs)

        TmGeoAnnotation ann1 = neuron1.getGeoAnnotationMap().get(endpoints.getFirstAnnotationID());
        TmGeoAnnotation ann2 = neuron2.getGeoAnnotationMap().get(endpoints.getSecondAnnotationID());

        boolean order1 = annotationAtPoint(ann1, points.get(0)) && annotationAtPoint(ann2, points.get(points.size() - 1));
        boolean order2 = annotationAtPoint(ann2, points.get(0)) && annotationAtPoint(ann1, points.get(points.size() - 1));
        if (!order1 && !order2) {
            // something's been moved; we should log this?
            return;
        }


        // if a path between those endpoints exists, remove it first:
        if (neuron1.getAnchoredPathMap().containsKey(endpoints)) {
            removeAnchoredPath(neuron1, neuron1.getAnchoredPathMap().get(endpoints));
        }

        // transform point list and persist
        final TmAnchoredPath path = neuronManager.addAnchoredPath(neuron1, endpoints.getFirstAnnotationID(),
                endpoints.getSecondAnnotationID(), points);


        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireAnchoredPathAdded(path);
                activityLog.logAddAnchoredPath(getCurrentWorkspace().getId(), path.getId());
            }
        });

    }

    /**
     * used in addAnchoredPath; is the annotation's location, truncated to integer, the
     * same as the list of integers?
     */
    private boolean annotationAtPoint(TmGeoAnnotation annotation, List<Integer> pointList) {
        return Math.abs( annotation.getX().intValue() - pointList.get(0) ) < 5 &&
                Math.abs( annotation.getY().intValue() - pointList.get(1) ) < 5 &&
                Math.abs( annotation.getZ().intValue() - pointList.get(2) ) < 5;
    }

    /**
     * remove an anchored path between two annotations, if one exists;
     * should only be called within AnnotationModel, and it does not
     * persist neuron or do any other cleanup
     */
    private void removeAnchoredPath(TmNeuronMetadata neuron, final TmAnchoredPath path) throws  Exception {
        // Remove the anchor path from its containing neuron
        neuron.getAnchoredPathMap().remove(path.getEndpoints());

        final ArrayList<TmAnchoredPath> pathList = new ArrayList<>();
        pathList.add(path);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireAnchoredPathsRemoved(pathList);
                activityLog.logRemoveAnchoredPath(getCurrentWorkspace().getId(), path.getId());
            }
        });
    }

    private void removeAnchoredPath(TmNeuronMetadata neuron, TmGeoAnnotation annotation1, TmGeoAnnotation annotation2)
            throws Exception {
        if (eitherIsNull(annotation1, annotation2)) {
            return;
        }

        // we assume second annotation is in same neuron; if it's not, there's no path
        //  to remove anyway
        TmAnchoredPathEndpoints endpoints = new TmAnchoredPathEndpoints(annotation1.getId(),
                annotation2.getId());
        if (neuron.getAnchoredPathMap().containsKey(endpoints)) {
            removeAnchoredPath(neuron, neuron.getAnchoredPathMap().get(endpoints));
        }
    }

    public String getNote(Long annotationID, TmNeuronMetadata neuron) {
        final TmStructuredTextAnnotation textAnnotation = neuron.getStructuredTextAnnotationMap().get(annotationID);
        if (textAnnotation != null) {
            JsonNode rootNode = textAnnotation.getData();
            JsonNode noteNode = rootNode.path("note");
            if (!noteNode.isMissingNode()) {
                return noteNode.asText();
            }
        }
        return "";
    }

    public String getNote(Long annotationID) {
        TmNeuronMetadata neuron = getNeuronFromAnnotationID(annotationID);
        return getNote(annotationID, neuron);
    }

    /**
     * add or update a note on a geometric annotation
     */
    public synchronized void setNote(final TmGeoAnnotation geoAnnotation, final String noteString) throws Exception {
        TmNeuronMetadata neuron = getNeuronFromAnnotationID(geoAnnotation.getId());
        if (neuron == null) {
            throw new Exception("can't find neuron for annotation with ID " + geoAnnotation.getId());
        }

        TmStructuredTextAnnotation textAnnotation = neuron.getStructuredTextAnnotationMap().get(geoAnnotation.getId());
        ObjectMapper mapper = new ObjectMapper();
        String jsonString = "";
        if (textAnnotation != null) {
            // if you've got a structured text annotation already, use it; for now, you only get one
            JsonNode rootNode = textAnnotation.getData();
            if (noteString.length() > 0) {
                ((ObjectNode) rootNode).put("note", noteString);
                jsonString = mapper.writeValueAsString(rootNode);
                neuronManager.updateStructuredTextAnnotation(neuron, textAnnotation, jsonString);
            } else {
                // there is a note attached, but we want it gone; if it's the only thing there,
                //  delete the whole structured text annotation
                ((ObjectNode) rootNode).remove("note");
                if (rootNode.size() > 0) {
                    jsonString = mapper.writeValueAsString(rootNode);
                    neuronManager.updateStructuredTextAnnotation(neuron, textAnnotation, jsonString);
                } else {
                    // otherwise, there's something left, so persist it (note: as of this
                    //  writing, there aren't any other structured text annotations besides
                    //  note, but no need to get sloppy!)
                    neuronManager.deleteStructuredTextAnnotation(neuron, textAnnotation.getParentId());
                }
            }

        } else {
            // it doesn't exist; if input is also null, don't even bother
            if (noteString.length() > 0) {
                ObjectNode rootNode = mapper.createObjectNode();
                rootNode.put("note", noteString);

                jsonString = mapper.writeValueAsString(rootNode);
                textAnnotation = neuronManager.addStructuredTextAnnotation(neuron, geoAnnotation.getId(), jsonString);
            }
        }

        // update modification date in for the geo annotation whose text annotation
        //  we changed; get it fresh; the object may have changed
        TmGeoAnnotation updatedAnn = getGeoAnnotationFromID(geoAnnotation.getId());
        updatedAnn.updateModificationDate();

        // Send the data back to the server to save.
        neuronManager.saveNeuronData(neuron);

        final TmWorkspace workspace = getCurrentWorkspace();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireNotesUpdated(workspace);
                activityLog.logSetNote(workspace.getId(), geoAnnotation.getId(), noteString);
            }
        });

    }

    public synchronized void removeNote(final TmStructuredTextAnnotation textAnnotation) throws Exception {

        final TmWorkspace workspace = getCurrentWorkspace();
        TmNeuronMetadata neuron = getNeuronFromAnnotationID(textAnnotation.getParentId());
        neuronManager.deleteStructuredTextAnnotation(neuron, textAnnotation.getParentId());
        TmGeoAnnotation ann = getGeoAnnotationFromID(textAnnotation.getParentId());
        ann.updateModificationDate();
        neuronManager.saveNeuronData(neuron);

        // updates
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireNotesUpdated(workspace);
                activityLog.logRemoveNote(workspace.getId(), textAnnotation.getParentId());
            }
        });
    }

    public void setNeuronVisibility(final Collection<TmNeuronMetadata> neuronList, final boolean visibility) throws Exception {
        BulkNeuronStyleUpdate bulkNeuronStyleUpdate = new BulkNeuronStyleUpdate();
        bulkNeuronStyleUpdate.setNeuronIds(DomainUtils.getIds(neuronList));
        bulkNeuronStyleUpdate.setVisible(visibility);
        tmDomainMgr.updateNeuronStyles(bulkNeuronStyleUpdate);
    }
    
    /**
     * change the style for a neuron; synchronized because it could be
     * called from multiple threads, and the update is not atomic
     */
    public synchronized void setNeuronStyle(TmNeuronMetadata neuron, NeuronStyle style) throws Exception {
        ModelTranslation.updateNeuronStyle(style, neuron);
        neuronManager.saveNeuronMetadata(neuron);
        // fire change to listeners
        fireNeuronStyleChanged(neuron, style);
        activityLog.logSetStyle(getCurrentWorkspace().getId(), neuron.getId());
    }

    public synchronized void setNeuronColors(List<TmNeuronMetadata> neuronList, Color color) throws Exception {
        
        BulkNeuronStyleUpdate bulkNeuronStyleUpdate = new BulkNeuronStyleUpdate();
        bulkNeuronStyleUpdate.setNeuronIds(DomainUtils.getIds(neuronList));
        bulkNeuronStyleUpdate.setColorHex(ModelTranslation.getColorHex(color));
        tmDomainMgr.updateNeuronStyles(bulkNeuronStyleUpdate);

        Map<TmNeuronMetadata, NeuronStyle> updateMap = new HashMap<>();
        for (TmNeuronMetadata neuron : neuronList) {
            neuron.setColor(color);
            updateMap.put(neuron, ModelTranslation.translateNeuronStyle(neuron));
        }
        
        fireNeuronStylesChanged(updateMap);
    }

    /**
     * retrieve a neuron style for a neuron, whether stored or default
     */
    public NeuronStyle getNeuronStyle(TmNeuronMetadata neuron) {
        return ModelTranslation.translateNeuronStyle(neuron);
    }

    public boolean automatedRefinementEnabled() {
        return getCurrentWorkspace().isAutoPointRefinement();
    }

    public boolean automatedTracingEnabled() {
        return getCurrentWorkspace().isAutoTracing();
    }

    /**
     * examine the input annotation and remove any predefined notes which
     * are no longer valid
     */
    private synchronized void stripPredefNotes(TmNeuronMetadata neuron, Long annID) throws Exception {
        String noteText = getNote(annID, neuron);
        boolean modified = false;
        if (noteText.length() > 0) {
            List<PredefinedNote> predefList = PredefinedNote.findNotes(noteText);
            for (PredefinedNote predefNote: predefList) {
                if (!predefNote.isValid(neuron, annID)) {
                    // remove it!
                    noteText = noteText.replace(predefNote.getNoteText(), "");
                    modified = true;
                }
            }
            if (modified) {
                setNote(getGeoAnnotationFromID(annID), noteText);
            }
        }

    }

    /**
     * export the neurons in the input list into the given file, in swc format;
     * all neurons (and all their neurites!) are crammed into a single file
     */
    public void exportSWCData(File swcFile, int downsampleModulo, Collection<TmNeuronMetadata> neurons, Progress progress) throws Exception {

        log.info("Exporting {} neurons to SWC file {}",neurons.size(),swcFile);
        progress.setStatus("Creating headers");
        
        Map<Long,List<String>> neuronHeaders = new HashMap<>();
        for (TmNeuronMetadata neuron: neurons) {
            List<String> headers = neuronHeaders.get(neuron.getId());
            if (headers == null) {
                headers = new ArrayList<>();
                neuronHeaders.put(neuron.getId(), headers);
            }
            NeuronStyle style = getNeuronStyle(neuron);
            float[] color = style.getColorAsFloatArray();
            headers.add(String.format(COLOR_FORMAT, color[0], color[1], color[2]));
            if (neurons.size() > 1) {
                // Allow user to pick name as name of file, if saving individual neuron.
                // Do not save the internal name.
                headers.add(String.format(NAME_FORMAT, neuron.getName()));
            }
        }
        
        progress.setStatus("Exporting neuron files");

        // get swcdata via converter, then write; conversion from TmNeurons is done
        //  all at once so all neurons are off set from the same center of mass
        // First write one file per neuron.
        List<SWCData> swcDatas = swcDataConverter.fromTmNeuron(neurons, neuronHeaders, downsampleModulo);
        int total = swcDatas.size()+1;
        if (swcDatas != null && !swcDatas.isEmpty()) {
            int i = 0;
            for (SWCData swcData: swcDatas) {
                progress.setStatus("Exporting neuron file "+(i+1));
                if (swcDatas.size() == 1) {
                    swcData.write(swcFile, -1);
                }
                else {
                    swcData.write(swcFile, i);
                }
                progress.setProgress(i, total);
                i++;
            }
        }

        progress.setStatus("Exporting combined file");
        
        // Next write one file containing all neurons, if there are more than one.
        if (swcDatas != null  &&  swcDatas.size() > 1) {
            SWCData swcData = swcDataConverter.fromAllTmNeuron(neurons, downsampleModulo);
            if (swcData != null) {
                swcData.write(swcFile);
                activityLog.logExportSWCFile(getCurrentWorkspace().getId(), swcFile.getName());
            }
        }

        progress.setProgress(total, total);
        progress.setStatus("Done");
    }

    // TODO: This method seems to be almost exactly the same as the one on the server-side (TiledMicroscopeDAO), except this one
    // updates the SimpleWorker. These code bases should be factored in a common class in the Shared modules.
    public synchronized TmNeuronMetadata importBulkSWCData(final File swcFile, TmWorkspace tmWorkspace, Progress progress) throws Exception {

        log.info("Importing neuron from SWC file {}",swcFile);
        
        // the constructor also triggers the parsing, but not the validation
        SWCData swcData = SWCData.read(swcFile);
        if (!swcData.isValid()) {
            throw new Exception(String.format("invalid SWC file %s; reason: %s",
                    swcFile.getName(), swcData.getInvalidReason()));
        }

        // note from CB, July 2013: Vaa3d can't handle large coordinates in swc files,
        //  so he added an OFFSET header and recentered on zero when exporting
        // therefore, if that header is present, respect it
        double[] externalOffset = swcData.parseOffset();

        // create one neuron for the file; take name from the filename (strip extension)
        String neuronName = swcData.parseName();
        if (neuronName == null) {
            neuronName = swcFile.getName();
        }
        if (neuronName.endsWith(SWCData.STD_SWC_EXTENSION)) {
            neuronName = neuronName.substring(0, neuronName.length() - SWCData.STD_SWC_EXTENSION.length());
        }

        // Must create the neuron up front, because we need the id when adding the linked geometric annotations below.
        TmNeuronMetadata neuron = neuronManager.createTiledMicroscopeNeuron(tmWorkspace, neuronName);

        // Bulk update in play.
        // and as long as we're doing brute force, we can update progress
        //  granularly (if we have a worker); start with 5% increments (1/20)
        int totalLength = swcData.getNodeList().size();
        int updateFrequency = totalLength / 20;
        if (updateFrequency == 0) {
            updateFrequency = 1;
        }
        if (progress != null) {
            progress.setProgress(0L, totalLength);
        }

        Map<Integer, Integer> nodeParentLinkage = new HashMap<>();

        Map<Integer, TmGeoAnnotation> annotations = new HashMap<>();
        for (SWCNode node : swcData.getNodeList()) {
            // Internal points, as seen in annotations, are same as external
            // points in SWC: represented as voxels. --LLF
            double[] internalPoint = swcDataConverter.internalFromExternal(
                    new double[]{
                            node.getX() + externalOffset[0],
                            node.getY() + externalOffset[1],
                            node.getZ() + externalOffset[2],}
            );

            // Build an external, unblessed annotation.  Set the id to the index.
            Date now = new Date();
            TmGeoAnnotation unserializedAnnotation = new TmGeoAnnotation(
                    new Long(node.getIndex()), null, neuron.getId(),
                    internalPoint[0], internalPoint[1], internalPoint[2], node.getRadius(),
                    now, now
            );
            
            annotations.put(node.getIndex(), unserializedAnnotation);
            nodeParentLinkage.put(node.getIndex(), node.getParentIndex());
            
            if (progress != null && (node.getIndex() % updateFrequency) == 0) {
                progress.setProgress(node.getIndex(), totalLength);
            }
        }

        // Fire off the bulk update.  The "un-serialized" or
        // db-unknown annotations could be swapped for "blessed" versions.
        neuronManager.addLinkedGeometricAnnotationsInMemory(nodeParentLinkage, annotations, neuron);
        
        // Set neuron color
        float[] colorArr = swcData.parseColorFloats();
        if (colorArr != null) {
            Color color = new Color(colorArr[0], colorArr[1], colorArr[2]);
            neuron.setColor(color);
        }
        
        neuronManager.saveNeuronData(neuron);

        // add it to the workspace
        neuronManager.addNeuron(neuron);
        
        return neuron;
    }

    // and now we have all the NeuronTagMap methods...in each case, it's a simple
    //  wrapper where for mutating calls, we save the map and fire appropriate updates

    public Set<String> getPredefinedNeuronTags() {
        return currentTagMap.getPredefinedTags();
    }

    public Set<String> getAvailableNeuronTags() {
        Set<String> availableTags = new HashSet<>(getAllNeuronTags());
        availableTags.addAll(getPredefinedNeuronTags());
        return availableTags;
    }

    public Set<String> getNeuronTags(TmNeuronMetadata neuron) {
        return currentTagMap.getTags(neuron);
    }

    public Set<String> getAllNeuronTags() {
        return currentTagMap.getAllTags();
    }

    public Set<TmNeuronMetadata> getNeuronsForTag(String tag) {
        return currentTagMap.getNeurons(tag);
    }

    public boolean hasNeuronTag(TmNeuronMetadata neuron, String tag) {
        return currentTagMap.hasTag(neuron, tag);
    }
    
    public void addNeuronTag(String tag, TmNeuronMetadata neuron) throws Exception {
        addNeuronTag(tag, Arrays.asList(neuron));
    }

    public void addNeuronTag(String tag, List<TmNeuronMetadata> neuronList) throws Exception {
        tmDomainMgr.bulkEditNeuronTags(neuronList, Arrays.asList(tag), true);
        for (TmNeuronMetadata neuron: neuronList) {
            currentTagMap.addTag(tag, neuron);
            neuron.getTags().add(tag);
        }
        fireNeuronTagsChanged(neuronList);
    }

    public void removeNeuronTag(String tag, TmNeuronMetadata neuron) throws Exception {
        removeNeuronTag(tag, Arrays.asList(neuron));
    }

    public void removeNeuronTag(String tag, List<TmNeuronMetadata> neuronList) throws Exception {
        tmDomainMgr.bulkEditNeuronTags(neuronList, Arrays.asList(tag), false);
        for (TmNeuronMetadata neuron: neuronList) {
            currentTagMap.removeTag(tag, neuron);
            neuron.getTags().remove(tag);
        }
        fireNeuronTagsChanged(neuronList);
    }

    public void clearNeuronTags(TmNeuronMetadata neuron) throws Exception {
        tmDomainMgr.bulkEditNeuronTags(Arrays.asList(neuron), new ArrayList<>(neuron.getTags()), false);
        currentTagMap.clearTags(neuron);
        neuron.getTags().clear();
        fireNeuronTagsChanged(Arrays.asList(neuron));
    }

    /**
     * given the ID of an annotation, return an object wrapping it (or null)
     */
    private TmGeoAnnotation getGeoAnnotationFromID(TmNeuronMetadata foundNeuron, Long annotationID) {
        if (foundNeuron != null) {
            return foundNeuron.getGeoAnnotationMap().get(annotationID);
        } else {
            return null;
        }
    }

    public List<File> breakOutByRoots(File infile) throws IOException {
        return new SWCData().breakOutByRoots(infile);
    }

    public void fireAnnotationNotMoved(TmGeoAnnotation annotation) {
        for (TmGeoAnnotationModListener l: tmGeoAnnoModListeners) {
            l.annotationNotMoved(annotation);
        }
    }

    public void fireAnnotationMoved(TmGeoAnnotation annotation) {
        for (TmGeoAnnotationModListener l: tmGeoAnnoModListeners) {
            l.annotationMoved(annotation);
        }
    }

    public void fireAnnotationRadiusUpdated(TmGeoAnnotation annotation) {
        for (TmGeoAnnotationModListener l: tmGeoAnnoModListeners) {
            l.annotationRadiusUpdated(annotation);
        }
    }

    public synchronized void postWorkspaceUpdate(TmNeuronMetadata neuron) {
        final TmWorkspace workspace = getCurrentWorkspace();
        // update workspace; update and select new neuron; this will draw points as well
        fireWorkspaceLoaded(workspace);
        fireWorkspaceChanged();
        selectNeuron(neuron);
    }

    private boolean eitherIsNull(Object object1, Object object2) {
        return object1 == null || object2 == null;
    }

    void fireAnnotationMoved(Long annotationID) {
        fireAnnotationMoved(getGeoAnnotationFromID(annotationID));
    }

    void fireAnnotationNotMoved(Long annotationID) {
        fireAnnotationNotMoved(getGeoAnnotationFromID(annotationID));
    }

    void fireAnnotationRadiusUpdated(Long annotationID) {
        fireAnnotationRadiusUpdated(getGeoAnnotationFromID(annotationID));
    }

    void fireAnnotationAdded(TmGeoAnnotation annotation) {
        for (TmGeoAnnotationModListener l : tmGeoAnnoModListeners) {
            l.annotationAdded(annotation);
        }
    }

    void fireAnnotationsDeleted(List<TmGeoAnnotation> deleteList) {
        // undraw deleted annotation
        for (TmGeoAnnotationModListener l : tmGeoAnnoModListeners) {
            l.annotationsDeleted(deleteList);
        }
    }

    void fireAnnotationReparented(TmGeoAnnotation annotation) {
        for (TmGeoAnnotationModListener l : tmGeoAnnoModListeners) {
            l.annotationReparented(annotation);
        }
    }

    void fireAnchoredPathsRemoved(List<TmAnchoredPath> deleteList) {
        // undraw deleted annotation
        for (TmAnchoredPathListener l : tmAnchoredPathListeners) {
            l.removeAnchoredPaths(deleteList);
        }
    }

    void fireAnchoredPathAdded(TmAnchoredPath path) {
        for (TmAnchoredPathListener l : tmAnchoredPathListeners) {
            l.addAnchoredPath(path);
        }
    }

    void fireWorkspaceLoaded(TmWorkspace workspace) {
        for (GlobalAnnotationListener l: globalAnnotationListeners) {
            l.workspaceLoaded(workspace);
        }
    }

    void fireNeuronSelected(TmNeuronMetadata neuron) {
        for (GlobalAnnotationListener l: globalAnnotationListeners) {
            l.neuronSelected(neuron);
        }
        if (neuron!=null) {
            selectionModel.select(neuron, true, true);
        }
    }

    void fireNeuronStyleChanged(TmNeuronMetadata neuron, NeuronStyle style) {
        for (GlobalAnnotationListener l: globalAnnotationListeners) {
            l.neuronStyleChanged(neuron, style);
        }
    }

    void fireNeuronStylesChanged(Map<TmNeuronMetadata, NeuronStyle> neuronStyleMap) {
        for (GlobalAnnotationListener l: globalAnnotationListeners) {
            l.neuronStylesChanged(neuronStyleMap);
        }
    }

    void fireNeuronTagsChanged(List<TmNeuronMetadata> neuronList) {
        for (GlobalAnnotationListener l: globalAnnotationListeners) {
            l.neuronTagsChanged(neuronList);
        }
    }

    void fireNotesUpdated(TmWorkspace workspace) {
        if (notesUpdateListener != null) {
            notesUpdateListener.notesUpdated(workspace);
        }
    }

    private void fireWorkspaceChanged() {
    }

    public NeuronSet getNeuronSet() {
        return neuronSetAdapter;
    }
}
