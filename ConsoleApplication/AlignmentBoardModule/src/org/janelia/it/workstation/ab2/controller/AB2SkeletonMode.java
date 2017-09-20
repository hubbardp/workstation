package org.janelia.it.workstation.ab2.controller;

import org.janelia.it.workstation.ab2.event.AB2DomainObjectUpdateEvent;
import org.janelia.it.workstation.ab2.event.AB2Event;
import org.janelia.it.workstation.ab2.model.AB2SkeletonDomainObject;
import org.janelia.it.workstation.ab2.renderer.AB2SkeletonRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AB2SkeletonMode extends AB2View3DMode {

    Logger logger= LoggerFactory.getLogger(AB2SkeletonMode.class);


    public AB2SkeletonMode(AB2Controller controller, AB2SkeletonRenderer renderer) {
        super(controller, renderer);
        logger.info("AB2SkeletonMode() constructor finished");
    }

    @Override
    public void processEvent(AB2Event event) {
        //logger.info("processEvent()");
        super.processEvent(event);
        if  (event instanceof AB2DomainObjectUpdateEvent) {
            ((AB2SkeletonRenderer)renderer).setSkeleton(((AB2SkeletonDomainObject)controller.getDomainObject()).getSkeleton());
            controller.repaint();
        }
    }

}
