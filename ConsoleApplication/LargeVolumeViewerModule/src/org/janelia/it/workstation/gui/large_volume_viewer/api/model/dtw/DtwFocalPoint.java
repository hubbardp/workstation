package org.janelia.it.workstation.gui.large_volume_viewer.api.model.dtw;

/**
 * Focal point DTO for communicating with the Directed Tracing Workflow Service.
 * 
 * Represents the starting location and orientation of the camera when viewing a decision to be made.
 * 
 * @author <a href="mailto:rokickik@janelia.hhmi.org">Konrad Rokicki</a>
 */
public class DtwFocalPoint {

    private String centerLocation;
    private String normalVector;

    public String getCenterLocation() {
        return centerLocation;
    }

    public void setCenterLocation(String centerLocation) {
        this.centerLocation = centerLocation;
    }

    public String getNormalVector() {
        return normalVector;
    }

    public void setNormalVector(String normalVector) {
        this.normalVector = normalVector;
    }
}
