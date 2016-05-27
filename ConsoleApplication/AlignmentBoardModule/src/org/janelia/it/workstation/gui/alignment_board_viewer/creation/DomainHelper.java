/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.janelia.it.workstation.gui.alignment_board_viewer.creation;

import java.util.ArrayList;
import java.util.List;
import org.janelia.it.jacs.model.domain.DomainObject;
import org.janelia.it.jacs.model.domain.Reference;
import org.janelia.it.jacs.model.domain.ReverseReference;
import org.janelia.it.jacs.model.domain.compartments.Compartment;
import org.janelia.it.jacs.model.domain.compartments.CompartmentSet;
import org.janelia.it.jacs.model.domain.gui.alignment_board.AlignmentBoard;
import org.janelia.it.jacs.model.domain.gui.alignment_board.AlignmentContext;
import org.janelia.it.jacs.model.domain.sample.NeuronFragment;
import org.janelia.it.jacs.model.domain.sample.NeuronSeparation;
import org.janelia.it.jacs.model.domain.sample.ObjectiveSample;
import org.janelia.it.jacs.model.domain.sample.PipelineResult;
import org.janelia.it.jacs.model.domain.sample.Sample;
//import org.janelia.it.jacs.model.domain.sample.Sample;
import org.janelia.it.jacs.model.domain.sample.SampleAlignmentResult;
import org.janelia.it.jacs.model.domain.sample.SamplePipelineRun;
import org.janelia.it.jacs.model.domain.workspace.TreeNode;
import org.janelia.it.workstation.gui.browser.api.AccessManager;
import org.janelia.it.workstation.gui.browser.api.DomainMgr;
import org.janelia.it.workstation.gui.browser.api.DomainModel;
import org.janelia.it.workstation.gui.framework.session_mgr.SessionMgr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This helper class will walk around the domain data, finding various pieces
 * of data required for other operations.
 *
 * @author fosterl
 */
public class DomainHelper {
    public static final String ALIGNMENT_BOARDS_FOLDER = "Alignment Boards";

    private Logger log = LoggerFactory.getLogger(DomainHelper.class);
    public List<AlignmentContext> getAvailableAlignmentContexts(Sample sample) throws Exception {
        List<AlignmentContext> rtnVal = new ArrayList<>();
        if (sample.getObjectives() != null) {
            for (ObjectiveSample os : sample.getObjectiveSamples()) {
                if (! os.hasPipelineRuns()) {
                    continue;
                }
                List<DomainObject> completeList = DomainMgr.getDomainMgr().getModel().getAllDomainObjectsByClass(AlignmentContext.class.getName());
                for (SamplePipelineRun pipelineRun : os.getPipelineRuns()) {
                    for (PipelineResult result : pipelineRun.getResults()) {
                        if (result instanceof SampleAlignmentResult) {
                            SampleAlignmentResult sar = (SampleAlignmentResult)result;
                            String alignmentSpace = sar.getAlignmentSpace();
                            String imageSize = sar.getImageSize();
                            String opticalResolution = sar.getOpticalResolution();
                            
                            // Find out if this one has been "blessed".
                            AlignmentContext ctx = new AlignmentContext();
                            ctx.setAlignmentSpace(alignmentSpace);
                            ctx.setOpticalResolution(opticalResolution);
                            ctx.setImageSize(imageSize);

                            if (completeList.contains(ctx)) {
                                rtnVal.add(ctx);
                            }
                            else {
                                log.warn("Failed to find context {} among existing.  Rejecting.", ctx);
                            }
                        }
                    }
                }
            }
        }
        return rtnVal;
    }
    
    public List<AlignmentContext> getAllAlignmentContexts() throws Exception {
        final DomainModel model = DomainMgr.getDomainMgr().getModel();
        List<DomainObject> completeList = model.getAllDomainObjectsByClass(AlignmentContext.class.getName());
        List<AlignmentContext> returnList = new ArrayList<>();
        for (DomainObject ctx: completeList) {
            returnList.add((AlignmentContext)ctx);
        }
        return returnList;
    }
    
    /** Creates a board, and returns its ID. */
    public AlignmentBoard createAlignmentBoard(AlignmentBoard board) throws Exception {
        AlignmentBoard rtnVal = null;
        if (board != null) {
            final DomainModel model = DomainMgr.getDomainMgr().getModel();
            rtnVal = (AlignmentBoard)model.save(board);
            if (rtnVal == null) {
                handleException("Failed to create an alignment board.  Null value returned.");
            }
            // Next step: add this new board appropriately to its parent.
            // Get the parent.
            List<TreeNode> nodes = (List<TreeNode>)model.getDomainObjects(TreeNode.class, ALIGNMENT_BOARDS_FOLDER);
            TreeNode alignmentBoardsFolder = null;
            if (nodes != null  &&  !nodes.isEmpty()) {
                for (TreeNode nextNode: nodes) {
                    if (nextNode.getOwnerKey().equals(AccessManager.getSubjectKey())) {
                        alignmentBoardsFolder = nextNode;
                    }
                }
            }
            else {
                // Must create the folder.
                alignmentBoardsFolder = new TreeNode();
                alignmentBoardsFolder.setName(ALIGNMENT_BOARDS_FOLDER);
                alignmentBoardsFolder = model.create(alignmentBoardsFolder);
            }
            model.addChild(alignmentBoardsFolder, rtnVal);
        }
        return rtnVal;
    }
    
    public AlignmentBoard fetchAlignmentBoard(Long alignmentBoardId) throws Exception {
        return (AlignmentBoard)DomainMgr.getDomainMgr().getModel().getDomainObject(AlignmentBoard.class.getSimpleName(), alignmentBoardId);
    }
    
    public Sample getSampleForNeuron(NeuronFragment nf) {
        Reference sampleRef = nf.getSample();
        return (Sample) DomainMgr.getDomainMgr().getModel().getDomainObject(sampleRef);
    }
    
    public ReverseReference getNeuronRRefForSample(Sample sample, String objective) {
        ObjectiveSample oSample = sample.getObjectiveSample(objective);
        SamplePipelineRun latestRun = oSample.getLatestRun();
        PipelineResult pResult = latestRun.getLatestResult();
        NeuronSeparation nResult = pResult.getLatestSeparationResult();
        return nResult.getFragmentsReference();
    }
    
    /**
     * Finds all refs in list which are compatible with an alignment board, and
     * inflates them back into the output list.
     *
     * @param ids list of reference ids to check.
     * @return compatible/inflated set of values.
     */
    public List<DomainObject> selectAndInflateCandidateObjects(List<Reference> ids) {
        List<DomainObject> domainObjects = new ArrayList<>();
        for (Reference id : ids) {
            if (id.getTargetClassName().equals(Sample.class.getSimpleName()) ||
                id.getTargetClassName().equals(NeuronFragment.class.getSimpleName()) ||
                id.getTargetClassName().equals(CompartmentSet.class.getSimpleName()) || 
                id.getTargetClassName().equals(Compartment.class.getSimpleName())) {
                
                domainObjects.add(DomainMgr.getDomainMgr().getModel().getDomainObject(id));
            }
        }
        return domainObjects;
    }

    private void handleException(String message) {
        Exception ex = new Exception(message);
        SessionMgr.getSessionMgr().handleException(ex);
    }
      
}

//                for (PipelineResult pResult: pipelineRun.getResults()) {
//                    NeuronSeparation ns = pResult.getLatestSeparationResult();
//                    // This will get me the fragments, later as needed.
//                }
