package org.janelia.workstation.lm.quicksearch;

import org.janelia.workstation.integration.util.FrameworkAccess;
import org.janelia.workstation.browser.gui.editor.FilterEditorPanel;
import org.janelia.workstation.core.model.search.SearchConfiguration;
import org.janelia.workstation.core.model.search.DomainObjectSearchResults;
import org.janelia.workstation.browser.actions.NewFilterActionListener;
import org.janelia.model.domain.gui.search.Filter;
import org.janelia.model.domain.sample.LSMImage;
import org.netbeans.spi.quicksearch.SearchProvider;
import org.netbeans.spi.quicksearch.SearchRequest;
import org.netbeans.spi.quicksearch.SearchResponse;

public class LSMSearchProvider implements SearchProvider {

    public void evaluate(SearchRequest request, SearchResponse response) {                
        String searchString = request.getText();

        Filter filter = FilterEditorPanel.createUnsavedFilter(LSMImage.class, null);
        filter.setSearchString(searchString);
        SearchConfiguration searchConfig = new SearchConfiguration(filter, DomainObjectSearchResults.PAGE_SIZE);
        Long numResults = null;
        try {
            numResults = searchConfig.performSearch().getNumTotalResults();
            if (numResults==0) {
                return;
            }
        }
        catch (Exception e) {
            FrameworkAccess.handleExceptionQuietly(e);
            return;
        }
        
        String title = String.format("Found %d LSMs containing '%s'. Click here to view.", numResults, searchString);
        
        if (!response.addResult(new OpenNewFilter(searchString), title)) {
            return;
        }
    }
    
    private static class OpenNewFilter implements Runnable {

        private String searchString;

        public OpenNewFilter(String searchString) {
            this.searchString = searchString;
        }

        @Override
        public void run() {
            NewFilterActionListener listener = new NewFilterActionListener(searchString, LSMImage.class);
            listener.actionPerformed(null);
        }
    }

}
