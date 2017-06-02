define(['../actions', '../../util/ajax'], function(actions, ajax) {
    actions.protectFromMain();

    const listName = { concept: 'concepts', property: 'properties', relationship: 'relationships' };
    const add = type => ({ workspaceId, key, ...rest }) => dispatch => {
        const obj = rest[type];
        return ajax('POST', `/ontology/${type}`, { workspaceId, ...obj })
            .then(payload => {
                dispatch(api.partial({ workspaceId, [listName[type]]: { [payload.title]: payload }}))
                if (key) {
                    dispatch(api.iriCreated({ key, type, iri: payload.title }))
                }
            })
    };

    const api = {
        get: ({ workspaceId, invalidate = false }) => (dispatch, getState) => {
            const state = getState();
            if (!workspaceId) {
                workspaceId = state.workspace.currentId ||
                    (state.user.current && state.user.current.currentWorkspaceId)
            }

            if (!workspaceId) throw new Error('No workspace provided');

            if (!state.ontology[workspaceId] || invalidate) {
                return ajax('GET', '/ontology', { workspaceId })
                    .then(result => {
                        dispatch(api.update({ ...transform(result), workspaceId }))
                    })
            }
        },

        update: (payload) => ({
            type: 'ONTOLOGY_UPDATE',
            payload
        }),

        partial: ({ workspaceId, ...ontology }) => (dispatch, getState) => {
            if (!workspaceId) {
                workspaceId = getState().workspace.currentId;
            }

            dispatch({
                type: 'ONTOLOGY_PARTIAL_UPDATE',
                payload: {
                    workspaceId,
                    ...transform(ontology)
                }
            })
        },

        addConcept: add('concept'),

        addProperty: add('property'),

        addRelationship: add('relationship'),

        iriCreated: ({ type, key, iri }) => ({
            type: 'ONTOLOGY_IRI_CREATED',
            payload: { type, key, iri }
        }),

        conceptsChange: ({ workspaceId, conceptIds }) => (dispatch, getState) => {
            const state = getState();
            const isPublishedChanged = !workspaceId;

            if (isPublishedChanged) {
                // FIXME: dispatch all ontology clear and load current workspace
            } else {
                const workspaceInStore = workspaceId in state.workspace.byId;
                if (workspaceInStore) {
                    return ajax('GET', '/ontology/segment', { conceptIds })
                        .then(payload => {
                            dispatch(api.partial({ workspaceId, ...payload }))
                        })
                }
            }
        }
    }

    return api;


    function transform(ontology) {
        const concepts = _.indexBy(ontology.concepts, 'title');
        const properties = _.indexBy(ontology.properties, 'title');
        const relationships = _.indexBy(ontology.relationships, 'title');

        return { concepts, properties, relationships };
    }
})

