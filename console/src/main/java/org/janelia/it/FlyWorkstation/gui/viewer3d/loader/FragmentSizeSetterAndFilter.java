package org.janelia.it.FlyWorkstation.gui.viewer3d.loader;

import org.janelia.it.FlyWorkstation.gui.alignment_board_viewer.renderable.MaskChanRenderableData;
import org.janelia.it.FlyWorkstation.gui.viewer3d.resolver.CacheFileResolver;
import org.janelia.it.FlyWorkstation.gui.viewer3d.resolver.FileResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Created with IntelliJ IDEA.
 * User: fosterl
 * Date: 8/5/13
 * Time: 11:58 AM
 *
 * Given a collection of renderable beans, this will eliminate from that list, all whose voxel count is less than
 * some threshold. All beans which have passed through the filter will have their voxel counts set to the found value.
 */
public class FragmentSizeSetterAndFilter {
    private Logger logger = LoggerFactory.getLogger(FragmentSizeSetterAndFilter.class);
    private long thresholdVoxelCount;

    public FragmentSizeSetterAndFilter(long thresholdVoxelCount) {
        this.thresholdVoxelCount = thresholdVoxelCount;
    }

    /**
     * Eliminates non-usables off the list.
     *
     * @param rawList whole list containing goods/bads.
     * @return limited list, with only acceptable values.
     */
    public Collection<MaskChanRenderableData> filter( Collection<MaskChanRenderableData> rawList ) {
        List<MaskChanRenderableData> rtnVal = new ArrayList<MaskChanRenderableData>();
        FileResolver resolver = new CacheFileResolver();
        int discardCount = 0;
        for ( MaskChanRenderableData data: rawList ) {
            // For each data, read up its voxel count.
            String maskPath = data.getMaskPath();
            if ( maskPath == null ) {
                rtnVal.add( data );
            }
            else if ( filter(resolver, maskPath, data) ) {
                rtnVal.add( data );
            }
            else {
                discardCount ++;
                logger.debug(
                        "Not keeping {}, file {}, because it has too few voxels.",
                        data.getBean().getLabelFileNum(),
                        maskPath
                );
            }

        }
        logger.info( "Discarded {} renderables.", discardCount );

        return rtnVal;
    }

    private boolean filter(FileResolver resolver, String maskPath, MaskChanRenderableData data) {
        File infile = new File( resolver.getResolvedFilename( maskPath ) );
        boolean rtnVal = false;
        if ( ! infile.canRead() ) {
            logger.warn("Mask file {} cannot be read.", infile );
        }
        else {
            try {
                MaskSingleFileLoader loader = new MaskSingleFileLoader( data.getBean() );
                FileInputStream fis = new FileInputStream( infile );
                long voxelCount = loader.getVoxelCount( fis );

                // Filter-in here.
                if ( data.isCompartment()  ||  voxelCount >= thresholdVoxelCount ) {
                    rtnVal = true;
                    logger.debug(
                            "Keeping {}, with {} voxels.",
                            infile, voxelCount
                    );
                }

                fis.close();

            } catch ( Exception ex ) {
                logger.error("Caught an exception while attempting to retrieve voxel count for {}.", maskPath );
                ex.printStackTrace();
            }
        }
        return rtnVal;
    }

}
