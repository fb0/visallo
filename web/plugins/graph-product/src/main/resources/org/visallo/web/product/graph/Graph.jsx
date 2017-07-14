define([
    'create-react-class',
    'prop-types',
    './Cytoscape',
    './popoverHelper',
    './styles',
    './GraphEmpty',
    './GraphExtensionViews',
    './popovers/index',
    'util/vertex/formatters',
    'util/retina',
    'components/RegistryInjectorHOC'
], function(
    createReactClass,
    PropTypes,
    Cytoscape,
    PopoverHelper,
    styles,
    GraphEmpty,
    GraphExtensionViews,
    Popovers,
    F,
    retina,
    RegistryInjectorHOC) {
    'use strict';

    const MaxPathsToFocus = 100;
    const MaxPreviewPopovers = 5;

    const noop = function() {};
    const generateCompoundEdgeId = edge => edge.outVertexId + edge.inVertexId + edge.label;
    const isGhost = cyElement => cyElement && cyElement._private && cyElement._private.data && cyElement._private.data.animateTo;
    const isValidElement = cyElement => cyElement && cyElement.is('.v,.e,.partial') && !isGhost(cyElement);
    const isValidNode = cyElement => cyElement && cyElement.is('node.v,node.partial') && !isGhost(cyElement);
    const edgeDisplay = (label, ontologyRelationships, edges) => {
        const display = label in ontologyRelationships ? ontologyRelationships[label].displayName : '';
        const showNum = edges.length > 1;
        const num = showNum ? ` (${F.number.pretty(edges.length)})` : '';
        return display + num;
    };
    const propTypesElementArrays = { vertices: PropTypes.array, edges: PropTypes.array };
    const propTypesElementObjects = { vertices: PropTypes.object, edges: PropTypes.object };

    let memoizeForStorage = {};
    const memoizeClear = (...prefixes) => {
        if (prefixes.length) {
            memoizeForStorage = _.omit(memoizeForStorage, (v, k) =>
                _.any(prefixes, prefix => k.indexOf(prefix) === 0));
        } else {
            memoizeForStorage = {};
        }
    }
    const memoizeFor = function(key, elements, fn, idFn) {
        if (!key) throw new Error('Cache key must be specified');
        if (!elements) throw new Error('Valid elements should be provided');
        if (!_.isFunction(fn)) throw new Error('Cache creation method should be provided');
        const fullKey = `${key}-${idFn ? idFn() : elements.id}`;
        const cache = memoizeForStorage[fullKey];
        const vertexChanged = cache && (_.isArray(cache.elements) ?
            (
                cache.elements.length !== elements.length ||
                _.any(cache.elements, (ce, i) => ce !== elements[i])
            ) : cache.elements !== elements
        );
        if (cache && !vertexChanged) {
            return cache.value
        }

        memoizeForStorage[fullKey] = { elements, value: fn() };
        return memoizeForStorage[fullKey].value
    }

    const Graph = createReactClass({

        propTypes: {
            workspace: PropTypes.shape({
                editable: PropTypes.bool
            }).isRequired,
            product: PropTypes.shape({
                previewMD5: PropTypes.string,
                extendedData: PropTypes.shape(propTypesElementArrays).isRequired
            }).isRequired,
            uiPreferences: PropTypes.shape({
                edgeLabels: PropTypes.bool
            }).isRequired,
            productElementIds: PropTypes.shape(propTypesElementArrays).isRequired,
            elements: PropTypes.shape({
                vertices: PropTypes.object,
                edges: PropTypes.object
            }).isRequired,
            selection: PropTypes.shape(propTypesElementObjects).isRequired,
            focusing: PropTypes.shape(propTypesElementObjects).isRequired,
            registry: PropTypes.object.isRequired,
            onUpdatePreview: PropTypes.func.isRequired,
            onVertexMenu: PropTypes.func,
            onEdgeMenu: PropTypes.func
        },

        getDefaultProps() {
            return {
                onVertexMenu: noop,
                onEdgeMenu: noop
            }
        },

        getInitialState() {
            return {
                viewport: this.props.viewport || {},
                animatingGhosts: {},
                initialProductDisplay: true,
                draw: null,
                paths: null,
                hovering: null
            }
        },

        saveViewport(props) {
            var productId = this.props.product.id;
            if (this.currentViewport && productId in this.currentViewport) {
                var viewport = this.currentViewport[productId];
                props.onSaveViewport(productId, viewport);
            }
        },

        componentDidMount() {
            memoizeClear();
            this.cyNodeIdsWithPositionChanges = {};

            this.popoverHelper = new PopoverHelper(this.node, this.cy);
            this.legacyListeners({
                addRelatedDoAdd: (event, data) => {
                    this.props.onAddRelated(this.props.product.id, data.addVertices)
                },
                selectAll: (event, data) => {
                    this.cytoscape.state.cy.elements().select();
                },
                selectConnected: (event, data) => {
                    event.stopPropagation();
                    const cy = this.cytoscape.state.cy;
                    const selected = cy.elements().filter(':selected');
                    selected.neighborhood('node').select();
                    selected.connectedNodes().select();

                    selected.unselect();
                },
                startVertexConnection: (event, { vertexId, connectionType }) => {
                    this.setState({
                        draw: {
                            vertexId,
                            connectionType
                        }
                    });
                },
                menubarToggleDisplay: { node: document, handler: (event, data) => {
                    if (data.name === 'products-full') {
                        this.teardownPreviews();
                    }
                }},
                finishedVertexConnection: this.cancelDraw,
                'zoomOut zoomIn fit': this.onKeyboard,
                createVertex: event => this.createVertex(),
                fileImportSuccess: this.onFileImportSuccess,
                previewVertex: this.previewVertex,
                closePreviewVertex: (event, { vertexId }) => {
                    delete this.detailPopoversMap[vertexId];
                },
                elementsCut: { node: document, handler: (event, { vertexIds }) => {
                    this.props.onRemoveElementIds({ vertexIds, edgeIds: [] });
                }},
                elementsPasted: { node: document, handler: (event, elementIds) => {
                    this.props.onDropElementIds(elementIds)
                }},
                focusPaths: { node: document, handler: this.onFocusPaths },
                defocusPaths: { node: document, handler: this.onDefocusPaths },
                focusPathsAddVertexIds: { node: document, handler: this.onFocusPathsAdd },
                reapplyGraphStylesheet: { node: document, handler: this.reapplyGraphStylesheet }
            });
        },

        componentWillReceiveProps(nextProps) {
            if (nextProps.selection !== this.props.selection) {
                this.resetQueuedSelection(nextProps.selection);
            }
            if (nextProps.registry !== this.props.registry) {
                memoizeClear();
            }
            if (nextProps.concepts !== this.props.concepts ||
                nextProps.relationships !== this.props.relationships) {
                memoizeClear('vertexToCyNode');
            }
            const newExtendedData = nextProps.product.extendedData;
            const oldExtendedData = this.props.product.extendedData;
            if (newExtendedData) {
                let shouldClear = false;
                const ignoredExtendedDataKeys = ['vertices', 'edges', 'unauthorizedEdgeIds'];
                Object.keys(newExtendedData).forEach(key => {
                    if (shouldClear || ignoredExtendedDataKeys.includes(key)) return;
                    if (!oldExtendedData || newExtendedData[key] !== oldExtendedData[key]) {
                        shouldClear = true;
                    }
                })
                if (shouldClear) {
                    memoizeClear('vertexToCyNode');
                }
            }
            if (nextProps.product.id === this.props.product.id) {
                this.setState({ viewport: {}, initialProductDisplay: false })
            } else {
                this.teardownPreviews();
                this.saveViewport(nextProps)
                this.setState({ viewport: nextProps.viewport || {}, initialProductDisplay: true })
            }
        },

        componentWillUnmount() {
            this.removeEvents.forEach(({ node, func, events }) => {
                $(node).off(events, func);
            })

            this.teardownPreviews();
            this.popoverHelper.destroy();
            this.popoverHelper = null;
            this.saveViewport(this.props)
        },

        teardownPreviews() {
            if (this.detailPopoversMap) {
                _.each(this.detailPopoversMap, e => $(e).teardownAllComponents())
                this.detailPopoversMap = {};
            }
        },

        render() {
            var { viewport, initialProductDisplay, draw, paths } = this.state,
                { panelPadding, registry, workspace, product } = this.props,
                { editable } = workspace,
                { previewMD5 } = product,
                config = {...CONFIGURATION(this.props), ...viewport},
                events = {
                    onSelect: this.onSelect,
                    onRemove: this.onRemove,
                    onUnselect: this.onUnselect,
                    onFree: this.onFree,
                    onLayoutStop: this.onLayoutStop,
                    onPosition: this.onPosition,
                    onReady: this.onReady,
                    onDecorationEvent: this.onDecorationEvent,
                    onMouseOver: this.onMouseOver,
                    onMouseOut: this.onMouseOut,
                    onTap: this.onTap,
                    onTapHold: this.onTapHold,
                    onTapStart: this.onTapStart,
                    onCxtTapStart: this.onTapStart,
                    onCxtTapEnd: this.onCxtTapEnd,
                    onContextTap: this.onContextTap,
                    onPan: this.onViewport,
                    onZoom: this.onViewport
                },
                menuHandlers = {
                    onMenuCreateVertex: this.onMenuCreateVertex,
                    onMenuSelect: this.onMenuSelect,
                    onMenuExport: this.onMenuExport
                },
                cyElements = this.mapPropsToElements(editable),
                extensionViews = registry['org.visallo.graph.view'];

            return (
                <div ref={r => {this.node = r}} className="org-visallo-graph" style={{ height: '100%' }}>
                    <Cytoscape
                        ref={r => { this.cytoscape = r}}
                        {...events}
                        {...menuHandlers}
                        tools={this.getTools()}
                        initialProductDisplay={initialProductDisplay}
                        hasPreview={Boolean(previewMD5)}
                        config={config}
                        panelPadding={panelPadding}
                        elements={cyElements}
                        drawEdgeToMouseFrom={draw ? _.pick(draw, 'vertexId', 'toVertexId') : null }
                        drawPaths={paths ? _.pick(paths, 'paths', 'sourceId', 'targetId') : null }
                        onGhostFinished={this.props.onGhostFinished}
                        onUpdatePreview={this.onUpdatePreview}
                        editable={editable}
                    ></Cytoscape>

                    {cyElements.nodes.length === 0 ? (
                        <GraphEmpty editable={editable} panelPadding={panelPadding} onSearch={this.props.onSearch} onCreate={this.onCreate} />
                    ) : null}

                    { extensionViews.length ? (
                        <GraphExtensionViews views={extensionViews} panelPadding={panelPadding} />
                    ) : null }
                </div>
            )
        },

        onFocusPaths(event, data) {
            if (data.paths.length > MaxPathsToFocus) {
                data.paths = data.paths.slice(0, MaxPathsToFocus);
                $(document).trigger('displayInformation', { message: 'Too many paths to show, will display the first ' + MaxPathsToFocus })
            }
            this.setState({
                paths: data
            })
        },

        onFocusPathsAdd(event) {
            const { paths } = this.state;
            if (paths) {
                const limitedPaths = paths.paths.slice(0, MaxPathsToFocus);
                const vertexIds = _.chain(limitedPaths).flatten().uniq().value();
                this.props.onDropElementIds({ vertexIds });
            }
        },

        onDefocusPaths(event, data) {
            if (this.state.paths) {
                this.setState({ paths: null });
            }
        },

        onCreate() {
            this.createVertex();
        },

        reapplyGraphStylesheet() {
            this.forceUpdate();
        },

        getTools() {
            /**
             * @typedef org.visallo.graph.options~Component
             * @property {object} cy The cytoscape instance
             * @property {object} product The graph product
             */
            return this.props.registry['org.visallo.graph.options'].map(e => ({
                identifier: e.identifier,
                componentPath: e.optionComponentPath,
                product: this.props.product
            }));
        },

        onReady({ cy }) {
            this.cy = cy;
        },

        onDecorationEvent(event) {
            const { cy, target } = event;
            const decoration = decorationForId(target.id());
            if (decoration) {
                const handlerName = {
                    /**
                     * @callback org.visallo.graph.node.decoration~onClick
                     * @this The decoration cytoscape node
                     * @param {object} event The {@link http://js.cytoscape.org/#events/event-object|Cytoscape event} object
                     * @param {object} data
                     * @param {object} data.vertex The vertex this decoration
                     * is attached
                     * @param {object} data.cy The cytoscape instance
                     */
                    tap: 'onClick',
                    /**
                     * @callback org.visallo.graph.node.decoration~onMouseOver
                     * @this The decoration cytoscape node
                     * @param {object} event The {@link http://js.cytoscape.org/#events/event-object|Cytoscape event} object
                     * @param {object} data
                     * @param {object} data.vertex The vertex this decoration
                     * is attached
                     * @param {object} data.cy The cytoscape instance
                     */
                    mouseover: 'onMouseOver',
                    /**
                     * @callback org.visallo.graph.node.decoration~onMouseOut
                     * @this The decoration cytoscape node
                     * @param {object} event The {@link http://js.cytoscape.org/#events/event-object|Cytoscape event} object
                     * @param {object} data
                     * @param {object} data.vertex The vertex this decoration
                     * is attached
                     * @param {object} data.cy The cytoscape instance
                     */
                    mouseout: 'onMouseOut'
                }[event.type];
                if (_.isFunction(decoration.onClick)) {
                    if (handlerName === 'onMouseOver') {
                        this.node.style.cursor = 'pointer';
                    } else if (handlerName === 'onMouseOut' || handlerName === 'onClick') {
                        this.node.style.cursor = null;
                    }
                }
                if (_.isFunction(decoration[handlerName])) {
                    decoration[handlerName].call(target, event, {
                        cy,
                        vertex: target.data('vertex')
                    });
                }
            }
        },

        onMouseOver({ cy, target }) {
            clearTimeout(this.hoverMouseOverTimeout);

            if (target !== cy && target.is('node.v')) {
                this.hoverMouseOverTimeout = _.delay(() => {
                    if (target.data('isTruncated')) {
                        var nId = target.id();
                        this.setState({ hovering: nId })
                    }
                }, 500);
            }
        },

        onMouseOut({ cy, target }) {
            clearTimeout(this.hoverMouseOverTimeout);
            if (target !== cy && target.is('node.v')) {
                if (this.state.hovering) {
                    this.setState({ hovering: null })
                }
            }
        },

        onFileImportSuccess(event, { vertexIds, position }) {
            const { x, y } = position;
            const { left, top } = this.node.getBoundingClientRect();
            const pos = this.droppableTransformPosition({
                x: x - left,
                y: y - top
            });
            this.props.onDropElementIds({vertexIds}, pos);
        },

        onKeyboard(event) {
            const { type } = event;
            const cytoscape = this.cytoscape;

            switch (type) {
                case 'fit': cytoscape.fit();
                    break;
                case 'zoomIn': cytoscape.onControlsZoom('in')
                    break;
                case 'zoomOut': cytoscape.onControlsZoom('out')
                    break;
                default:
                    console.warn(type);
            }
        },

        onMenuSelect(identifier) {
            const cy = this.cytoscape.state.cy;
            const selector = _.findWhere(
                this.props.registry['org.visallo.graph.selection'],
                { identifier }
            );
            if (selector) {
                selector(cy);
            }
        },

        onMenuExport(componentPath) {
            var exporter = _.findWhere(
                    this.props.registry['org.visallo.graph.export'],
                    { componentPath }
                );

            if (exporter) {
                const cy = this.cytoscape.state.cy;
                const { product } = this.props;
                Promise.require('util/popovers/exportWorkspace/exportWorkspace').then(ExportWorkspace => {
                    ExportWorkspace.attachTo(cy.container(), {
                        exporter: exporter,
                        workspaceId: product.workspaceId,
                        productId: product.id,
                        cy: cy,
                        anchorTo: {
                            page: {
                                x: window.lastMousePositionX,
                                y: window.lastMousePositionY
                            }
                        }
                    });
                });
            }
        },

        onMenuCreateVertex({pageX, pageY }) {
            const position = { x: pageX, y: pageY };
            this.createVertex(position);
        },

        previewVertex(event, data) {
            const cy = this.cytoscape.state.cy;

            Promise.all([
                Promise.require('util/popovers/detail/detail'),
                F.vertex.getVertexIdsFromDataEventOrCurrentSelection(data, { async: true })
            ]).spread((DetailPopover, ids) => {
                if (!this.detailPopoversMap) {
                    this.detailPopoversMap = {};
                }
                const currentPopovers = Object.keys(this.detailPopoversMap);
                const remove = _.intersection(ids, currentPopovers);
                var add = _.difference(ids, currentPopovers)

                remove.forEach(id => {
                    const cyNode = cy.getElementById(id);
                    if (cyNode.length) {
                        $(this.detailPopoversMap[id]).teardownAllComponents().remove();
                        delete this.detailPopoversMap[id];
                    }
                })
                const availableToOpen = MaxPreviewPopovers - (currentPopovers.length - remove.length);
                if (add.length && add.length > availableToOpen) {
                    $(this.node).trigger('displayInformation', { message: i18n('popovers.preview_vertex.too_many', MaxPreviewPopovers) });
                    add = add.slice(0, Math.max(0, availableToOpen));
                }

                add.forEach(id => {
                    var $popover = $('<div>').addClass('graphDetailPanePopover').appendTo(this.node);
                    this.detailPopoversMap[id] = $popover[0];
                    DetailPopover.attachTo($popover[0], {
                        vertexId: id,
                        anchorTo: {
                            vertexId: id
                        }
                    });
                })
            });
        },

        createVertex(position) {
            if (!position) {
                position = { x: window.lastMousePositionX, y: window.lastMousePositionY };
            }


            if (this.props.workspace.editable) {
                Promise.require('util/popovers/fileImport/fileImport')
                    .then(CreateVertex => {
                        CreateVertex.attachTo(this.node, {
                            anchorTo: { page: position }
                        });
                    });
            }
        },

        onUpdatePreview(data) {
            this.props.onUpdatePreview(this.props.product.id, data)
        },

        cancelDraw() {
            const cy = this.cytoscape.state.cy;
            cy.autoungrabify(false);
            this.setState({ draw: null })
        },

        onTapHold({ cy, target }) {
            if (cy !== target) {
                this.previewVertex(null, { vertexId: target.id() })
            }
        },

        onTapStart(event) {
            const { cy, target } = event;
            if (cy !== target && event.originalEvent.ctrlKey) {
                cy.autoungrabify(true);
                this.setState({
                    draw: {
                        vertexId: target.id()
                    }
                });
            }
        },

        onTap(event) {
            const { cy, target, position } = event;
            const { x, y } = position;
            const { ctrlKey, shiftKey } = event.originalEvent;
            const { draw, paths } = this.state;

            if (paths) {
                if (cy === target && _.isEmpty(this.props.selection.vertices) && _.isEmpty(this.props.selection.edges)) {
                    $(document).trigger('defocusPaths');
                    this.setState({ paths: null })
                }
            }
            if (draw) {
                const upElement = cy.renderer().findNearestElement(x, y, true, false);
                if (!upElement || draw.vertexId === upElement.id()) {
                    this.cancelDraw();
                    if (ctrlKey && upElement) {
                        this.onContextTap(event);
                    }
                } else if (!upElement.isNode()) {
                    this.cancelDraw();
                } else {
                    this.setState({ draw: {...draw, toVertexId: upElement.id() } });
                    this.showConnectionPopover();
                }
            } else {
                if (ctrlKey) {
                    this.onContextTap(event);
                } else if (!shiftKey && cy === target) {
                    this.coalesceSelection('clear');
                    this.props.onClearSelection();
                }
            }
        },

        onCxtTapEnd(event) {
            const { cy, target } = event;
            if (cy !== target && event.originalEvent.ctrlKey) {
                this.onTap(event);
            }
        },

        onContextTap(event) {
            const { target, cy, originalEvent } = event;
            // TODO: show all selected objects if not on item
            if (target !== cy) {
                const { pageX, pageY } = originalEvent;
                if (target.isNode()) {
                    this.props.onVertexMenu(originalEvent.target, target.id(), { x: pageX, y: pageY });
                } else {
                    const edgeIds = _.pluck(target.data('edgeInfos'), 'edgeId');
                    this.props.onEdgeMenu(originalEvent.target, edgeIds, { x: pageX, y: pageY });
                }
            }
        },

        onRemove({ target }) {
            if (isValidElement(target)) {
                this.coalesceSelection('remove', target.isNode() ? 'vertices' : 'edges', target);
            }
        },

        onSelect({ target }) {
            if (isValidElement(target)) {
                this.coalesceSelection('add', target.isNode() ? 'vertices' : 'edges', target);
            }
        },

        onUnselect({ target }) {
            if (isValidElement(target)) {
                this.coalesceSelection('remove', target.isNode() ? 'vertices' : 'edges', target);
            }
        },

        onLayoutStop() {
            this.sendPositionUpdates();
        },

        onFree() {
            this.sendPositionUpdates();
        },

        sendPositionUpdates() {
            if (!_.isEmpty(this.cyNodeIdsWithPositionChanges)) {
                this.props.onUpdatePositions(
                    this.props.product.id,
                    _.mapObject(this.cyNodeIdsWithPositionChanges, (cyNode, id) => retina.pixelsToPoints(cyNode.position()))
                );
                this.cyNodeIdsWithPositionChanges = {};
            }
        },

        onPosition({ target }) {
            if (isValidNode(target)) {
                var id = target.id();
                this.cyNodeIdsWithPositionChanges[id] = target;
            }
        },

        onViewport({ cy }) {
            var zoom = cy.zoom(), pan = cy.pan();
            if (!this.currentViewport) this.currentViewport = {};
            const viewport = { zoom, pan: {...pan}};
            this.currentViewport[this.props.product.id] = viewport;
        },

        droppableTransformPosition(rpos) {
            const cy = this.cytoscape.state.cy;
            const pan = cy.pan();
            const zoom = cy.zoom();
            return retina.pixelsToPoints({
                x: (rpos.x - pan.x) / zoom,
                y: (rpos.y - pan.y) / zoom
            });
        },

        mapPropsToElements(editable) {
            const { selection, ghosts, productElementIds, elements, relationships, registry, focusing } = this.props;
            const { hovering } = this.state;
            const { vertices: productVertices, edges: productEdges } = productElementIds;
            const { vertices, edges } = elements;
            const { vertices: verticesSelectedById, edges: edgesSelectedById } = selection;
            const nodeIds = {};
            const cyNodeConfig = (id, pos, data) => {
                if (data) {
                    nodeIds[id] = true;
                    return {
                        group: 'nodes',
                        data,
                        classes: mapVertexToClasses(id, vertices, focusing, registry['org.visallo.graph.node.class']),
                        position: retina.pointsToPixels(pos),
                        selected: (id in verticesSelectedById),
                        grabbable: editable
                    }
                }
            };
            const cyNodes = productVertices.reduce((nodes, { id, pos }) => {
                const data = mapVertexToData(id, vertices, registry['org.visallo.graph.node.transformer'], hovering);
                const cyNode = cyNodeConfig(id, pos, data);

                if (cyNode && ghosts && id in ghosts) {
                    const ghostData = {
                        ...cyNode.data,
                        id: `${cyNode.data.id}-ANIMATING`,
                        animateTo: {
                            id: data.id,
                            pos: { ...cyNode.position }
                        }
                    };
                    delete ghostData.parent;
                    nodes.push({
                        ...cyNode,
                        data: ghostData,
                        position: retina.pointsToPixels(ghosts[id]),
                        grabbable: false,
                        selectable: false
                    });
                }

                if (id in vertices) {
                    const markedAsDeleted = vertices[id] === null;
                    if (markedAsDeleted) {
                        return nodes;
                    }
                    const vertex = vertices[id];
                    const applyDecorations = memoizeFor('org.visallo.graph.node.decoration#applyTo', vertex, () => {
                        return _.filter(registry['org.visallo.graph.node.decoration'], function(e) {
                            /**
                             * @callback org.visallo.graph.node.decoration~applyTo
                             * @param {object} vertex
                             * @returns {boolean} Whether the decoration should be
                             * added to the node representing the vertex
                             */
                            return !_.isFunction(e.applyTo) || e.applyTo(vertex);
                        });
                    });
                    if (applyDecorations.length) {
                        const parentId = 'decP' + id;
                        cyNode.data.parent = parentId;
                        const decorations = memoizeFor('org.visallo.graph.node.decoration#data', vertex, () => {
                            return applyDecorations.map(dec => {
                                const data = mapDecorationToData(dec, vertex, () => this.forceUpdate());
                                if (!data) {
                                    return;
                                }
                                var { padding } = dec;
                                return {
                                    group: 'nodes',
                                    classes: mapDecorationToClasses(dec, vertex),
                                    data: {
                                        ...data,
                                        id: idForDecoration(dec, vertex.id),
                                        alignment: dec.alignment,
                                        padding,
                                        parent: parentId,
                                        vertex
                                    },
                                    position: { x: -1, y: -1 },
                                    grabbable: false,
                                    selectable: false
                                }
                            })
                        });

                        nodes.push({
                            group: 'nodes',
                            data: { id: parentId },
                            classes: 'decorationParent',
                            selectable: false,
                            grabbable: false
                        });
                        nodes.push(cyNode);
                        decorations.forEach(d => {
                            if (d) nodes.push(d);
                        });
                    } else if (cyNode) {
                        nodes.push(cyNode);
                    }
                } else if (cyNode) {
                    nodes.push(cyNode);
                }

                return nodes
            }, []);

            const cyEdges = _.chain(productEdges)
                .filter(edgeInfo => {
                    const elementMarkedAsDeletedInStore =
                        edgeInfo.edgeId in edges &&
                        edges[edgeInfo.edgeId] === null;
                    const edgeNodesExist = edgeInfo.inVertexId in nodeIds && edgeInfo.outVertexId in nodeIds;

                    return !elementMarkedAsDeletedInStore && edgeNodesExist;
                })
                .groupBy(generateCompoundEdgeId)
                .map((edgeInfos, id) => {
                    const edgesForInfos = Object.values(_.pick(edges, _.pluck(edgeInfos, 'edgeId')));
                    return {
                        data: mapEdgeToData(id, edgeInfos, edgesForInfos, relationships, registry['org.visallo.graph.edge.transformer']),
                        classes: mapEdgeToClasses(edgeInfos, edgesForInfos, focusing, registry['org.visallo.graph.edge.class']),
                        selected: _.any(edgeInfos, e => e.edgeId in edgesSelectedById)
                    }
                })
                .value()

            return { nodes: cyNodes, edges: cyEdges };
        },

        resetQueuedSelection(sel) {
            this._queuedSelection = sel ? {
                add: { vertices: sel.vertices, edges: sel.edges },
                remove: {vertices: {}, edges: {}}
            } : { add: {vertices: {}, edges: {}}, remove: {vertices: {}, edges: {}} };

            if (!this._queuedSelectionTrigger) {
                this._queuedSelectionTrigger = _.debounce(() => {
                    const vertices = Object.keys(this._queuedSelection.add.vertices);
                    const edges = Object.keys(this._queuedSelection.add.edges);
                    if (vertices.length || edges.length) {
                        this.props.onSetSelection({ vertices, edges })
                    } else {
                        this.props.onClearSelection();
                    }
                }, 100);
            }
        },

        coalesceSelection(action, type, cyElementOrId) {
            if (!this._queuedSelection) {
                this.resetQueuedSelection();
            }
            var id = cyElementOrId;

            if (cyElementOrId && _.isFunction(cyElementOrId.data)) {
                if (type === 'edges') {
                    cyElementOrId.data('edgeInfos').forEach(edgeInfo => {
                        this.coalesceSelection(action, type, edgeInfo.edgeId);
                    })
                    return;
                } else {
                    id = cyElementOrId.id();
                }
            }


            if (action !== 'clear') {
                this._queuedSelection[action][type][id] = id;
            }

            if (action === 'add') {
                delete this._queuedSelection.remove[type][id]
            } else if (action === 'remove') {
                delete this._queuedSelection.add[type][id]
            } else if (action === 'clear') {
                this._queuedSelection.add.vertices = {};
                this._queuedSelection.add.edges = {};
                this._queuedSelection.remove.vertices = {};
                this._queuedSelection.remove.edges = {};
            } else {
                console.warn('Unknown action: ', action)
            }

            this._queuedSelectionTrigger();
        },

        showConnectionPopover() {
            const cy = this.cytoscape.state.cy;
            const { connectionType, vertexId, toVertexId, connectionData } = this.state.draw;
            const Popover = Popovers(connectionType);
            Popover.teardownAll();
            Popover.attachTo(this.node, {
                cy,
                cyNode: cy.getElementById(toVertexId),
                otherCyNode: cy.getElementById(vertexId),
                edge: cy.$('edge.drawEdgeToMouse'),
                outVertexId: vertexId,
                inVertexId: toVertexId,
                connectionData
            });
        },

        legacyListeners(map) {
            this.removeEvents = [];

            _.each(map, (handler, events) => {
                var node = this.node;
                var func = handler;
                if (!_.isFunction(handler)) {
                    node = handler.node;
                    func = handler.handler;
                }
                this.removeEvents.push({ node, func, events });
                $(node).on(events, func);
            })
        }
    });


    const mapEdgeToData = (id, edgeInfos, edges, ontologyRelationships, transformers) => {
        return memoizeFor('org.visallo.graph.edge.transformer', edges, () => {
            const { inVertexId, outVertexId, label } = edgeInfos[0];
            const base = {
                id,
                source: outVertexId,
                target: inVertexId,
                type: label,
                label: edgeDisplay(label, ontologyRelationships, edgeInfos),
                edgeInfos,
                edges
            };

            if (edges.length) {
                return transformers.reduce((data, fn) => {

                    /**
                     * Mutate the object to change the edge data.
                     *
                     * @callback org.visallo.graph.edge.transformer~transformerFn
                     * @param {object} data The cytoscape data object
                     * @param {string} data.source The source vertex id
                     * @param {string} data.target The target vertex id
                     * @param {string} data.type The edge label IRI
                     * @param {string} data.label The edge label display value
                     * @param {array.<object>} data.edgeInfos
                     * @param {array.<object>} data.edges
                     * @example
                     * function transformer(data) {
                     *     data.myCustomAttr = '';
                     * }
                     */
                    fn(data)
                    return data;
                }, base)
            }

            return base;
        }, () => id)
    };
    const mapEdgeToClasses = (edgeInfos, edges, focusing, classers) => {
        let cls = [];
        if (edges.length) {

            /**
             * Mutate the classes array to adjust the classes.
             *
             * @callback org.visallo.graph.edge.class~classFn
             * @param {array.<object>} edges List of edges that are collapsed into the drawn line. `length >= 1`.
             * @param {string} type EdgeLabel of the collapsed edges.
             * @param {array.<string>} classes List of classes that will be added to cytoscape edge.
             * @example
             * function(edges, type, cls) {
             *     cls.push('org-example-cls');
             * }
             */

            cls = memoizeFor('org.visallo.graph.edge.class', edges, function() {
                const cls = [];
                classers.forEach(fn => fn(edges, edgeInfos.label, cls));
                cls.push('e');
                return cls;
            }, () => edges.map(e => e.id).sort())
        } else {
            cls.push('partial')
        }

        const classes = cls.join(' ');

        if (_.any(edgeInfos, info => info.edgeId in focusing.edges)) {
            return classes + ' focus';
        }
        return classes;
    };
    const decorationIdMap = {};
    const decorationForId = id => {
        return decorationIdMap[id];
    }
    const idForDecoration = (function() {
        const decorationIdCache = new WeakMap();
        const vertexIdCache = {};
        var decorationIdCacheInc = 0, vertexIdCacheInc = 0;
        return (decoration, vertexId) => {
            var id = decorationIdCache.get(decoration);
            if (!id) {
                id = decorationIdCacheInc++;
                decorationIdCache.set(decoration, id);
            }
            var vId;
            if (vertexId in vertexIdCache) {
                vId = vertexIdCache[vertexId];
            } else {
                vId = vertexIdCacheInc++;
                vertexIdCache[vertexId] = vId;
            }
            var full = `dec${vId}-${id}`;
            decorationIdMap[full] = decoration;
            return full;
        }
    })();
    const mapDecorationToData = (decoration, vertex, update) => {
        const getData = () => {
            var data;
            /**
             * _**Note:** This will be called for every vertex change event
             * (`verticesUpdated`). Cache/memoize the result if possible._
             *
             * @callback org.visallo.graph.node.decoration~data
             * @param {object} vertex
             * @returns {object} The cytoscape data object for a decoration
             * given a vertex
             */
            if (_.isFunction(decoration.data)) {
                data = decoration.data(vertex);
            } else if (decoration.data) {
                data = decoration.data;
            }
            if (!_.isObject(data)) {
                throw new Error('data is not an object', data)
            }
            var p = Promise.resolve(data);
            p.catch(e => console.error(e))
            p.tap(() => {
                update()
            });
            return p;
        };
        const getIfFulfilled = p => {
            if (p.isFulfilled()) return p.value();
        }
        return getIfFulfilled(getData());
    };
    const mapDecorationToClasses = (decoration, vertex) => {
        var cls = ['decoration'];

        if (_.isString(decoration.classes)) {
            cls = cls.concat(decoration.classes.trim().split(/\s+/));
        } else if (_.isFunction(decoration.classes)) {

            /**
             * @callback org.visallo.graph.node.decoration~classes
             * @param {object} vertex
             * @returns {array.<string>|string} The classnames to add to the
             * node, either an array of classname strings, or space-separated
             * string
             */
            var newClasses = decoration.classes(vertex);
            if (!_.isArray(newClasses) && _.isString(newClasses)) {
                newClasses = newClasses.trim().split(/\s+/);
            }
            if (_.isArray(newClasses)) {
                cls = cls.concat(newClasses)
            }
        }
        return cls.join(' ');
    };
    const mapVertexToClasses = (id, vertices, focusing, classers) => {
        let cls = [];
        if (id in vertices) {
            const vertex = vertices[id];

            /**
             * Mutate the classes array to adjust the classes.
             *
             * @callback org.visallo.graph.node.class~classFn
             * @param {object} vertex The vertex that represents the node
             * @param {array.<string>} classes List of classes that will be added to cytoscape node.
             * @example
             * function(vertex, cls) {
             *     cls.push('org-example-cls');
             * }
             */
            cls = memoizeFor('org.visallo.graph.node.class', vertex, function() {
                const cls = [];
                classers.forEach(fn => fn(vertex, cls));
                cls.push('v');
                return cls;
            })
        } else {
            cls.push('partial')
        }

        const classes = cls.join(' ');
        if (id in focusing.vertices) {
            return classes + ' focus';
        }
        return classes;
    };
    const vertexToCyNode = (vertex, transformers, hovering) => {
        const title = F.vertex.title(vertex);
        const result = memoizeFor('vertexToCyNode', vertex, function() {
            const truncatedTitle = F.string.truncate(title, 3);
            const conceptType = F.vertex.prop(vertex, 'conceptType');
            const imageSrc = F.vertex.image(vertex, null, 150);
            const selectedImageSrc = F.vertex.selectedImage(vertex, null, 150);
            const startingData = {
                id: vertex.id,
                isTruncated: title !== truncatedTitle,
                truncatedTitle,
                conceptType,
                imageSrc,
                selectedImageSrc
            };

            return transformers.reduce((data, t) => {
                /**
                 * Mutate the data object that gets passed to Cytoscape.
                 *
                 * @callback org.visallo.graph.node.transformer~transformerFn
                 * @param {object} vertex The vertex representing this node
                 * @param {object} data The cytoscape data object
                 * @example
                 * function transformer(vertex, data) {
                 *     data.myCustomAttr = '...';
                 * }
                 */
                t(vertex, data)
                return data;
            }, startingData);
        });

        if (hovering === vertex.id) {
            return { ...result, truncatedTitle: title }
        }

        return result;
    }
    const mapVertexToData = (id, vertices, transformers, hovering) => {
        if (id in vertices) {
            if (vertices[id] === null) {
                return;
            } else {
                const vertex = vertices[id];
                return vertexToCyNode(vertex, transformers, hovering);
            }
        } else {
            return { id }
        }
    };
    const CONFIGURATION = (props) => {
        const { pixelRatio, uiPreferences, product, registry } = props;
        const { edgeLabels } = uiPreferences;
        const edgesCount = product.extendedData.edges.length;
        const styleExtensions = registry['org.visallo.graph.style'];

        return {
            minZoom: 1 / 16,
            maxZoom: 6,
            hideEdgesOnViewport: false,
            hideLabelsOnViewport: false,
            textureOnViewport: true,
            boxSelectionEnabled: true,
            panningEnabled: true,
            userPanningEnabled: true,
            zoomingEnabled: true,
            userZoomingEnabled: true,
            style: styles({ pixelRatio, edgesCount, edgeLabels, styleExtensions })
        }
    };

    return RegistryInjectorHOC(Graph, [
        'org.visallo.graph.edge.class',
        'org.visallo.graph.edge.transformer',
        'org.visallo.graph.export',
        'org.visallo.graph.node.class',
        'org.visallo.graph.node.decoration',
        'org.visallo.graph.node.transformer',
        'org.visallo.graph.options',
        'org.visallo.graph.selection',
        'org.visallo.graph.style',
        'org.visallo.graph.view'
    ]);
});
