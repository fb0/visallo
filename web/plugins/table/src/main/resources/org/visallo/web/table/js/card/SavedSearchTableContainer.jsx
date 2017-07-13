define([
    'react-redux',
    'data/web-worker/store/selection/actions',
    'data/web-worker/store/product/selectors',
    'data/web-worker/store/ontology/selectors',
    './SavedSearchTableCard'
], function(
    redux,
    selectionActions,
    productSelectors,
    ontologySelectors,
    SavedSearchTableCard
) {
    'use strict';

    const SavedSearchTableContainer = redux.connect(

        (state, props) => ({
            ...props,
            editable: state.workspace.byId[state.workspace.currentId].editable,
            selection: state.selection.idsByType,
            concepts: ontologySelectors.getConcepts(state),
            relationships: ontologySelectors.getRelationships(state),
            properties: ontologySelectors.getProperties(state)
        }),

        (dispatch, props) => ({
            onSetSelection: (selection) => dispatch(selectionActions.set(selection))
        })
    )(SavedSearchTableCard);

    return SavedSearchTableContainer;
});
