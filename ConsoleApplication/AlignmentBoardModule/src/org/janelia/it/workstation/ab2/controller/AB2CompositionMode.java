package org.janelia.it.workstation.ab2.controller;

import java.awt.Point;

import javax.media.opengl.GLAutoDrawable;

import org.janelia.it.workstation.ab2.event.AB2Event;
import org.janelia.it.workstation.ab2.gl.GLRegion;
import org.janelia.it.workstation.ab2.gl.GLRegionManager;

public class AB2CompositionMode extends AB2ControllerMode {

    public AB2CompositionMode(AB2Controller controller) {
        super(controller);
    }

    @Override
    public GLRegion getRegionAtPosition(Point point) {
        return null;
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }

    @Override
    public void shutdown() {

    }

    @Override
    public GLRegionManager getRegionManager() {
        return null;
    }

    @Override
    public void processEvent(AB2Event event) {

    }

    @Override
    public void init(GLAutoDrawable glAutoDrawable) {

    }

    @Override
    public void dispose(GLAutoDrawable glAutoDrawable) {

    }

    @Override
    public void modeDisplay(GLAutoDrawable glAutoDrawable) {

    }

    @Override
    public void reshape(GLAutoDrawable glAutoDrawable, int i, int i1, int i2, int i3) {

    }

}
