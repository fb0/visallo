/**
 * Allows a user to select an ontology property from a searchable dropdown component.
 *
 * @module components/PropertySelect
 * @flight Dropdown selection component for selecting properties from the ontology
 * @attr {Array.<object>} properties The ontology properties to populate the list with
 * @attr {string} [placeholder=Select Property] the placeholder text to display
 * @attr {boolean} [showAdminConcepts=false] Whether concepts that aren't user visible should be displayed
 * @attr {boolean} [onlySearchable=false] Only show properties that have searchable attribute equal to true in ontology
 * @attr {boolean} [onlySortable=false] Only show properties that have sortable attribute equal to true in ontology
 * @attr {boolean} [rollupCompound=true] Hide all dependant properties and only show the compound/parent fields
 * @attr {boolean} [focus=false] Activate the field for focus when finished rendering
 * @attr {boolean} [clearable=true] Whether the user can clear the value in the field
 * @attr {number} [maxItems=-1] Limit the maximum items that are shown in search list (-1 signifies no limit)
 * @attr {string} [selectedProperty=''] Default the selection to this property IRI
 * @attr {Array.<string>} [unsupportedProperties=[]] Remove these property IRIs from the list
 * @fires module:components/PropertySelect#propertyselected
 * @listens module:components/PropertySelect#filterProperties
 * @example
 * dataRequest('ontology', 'properties').then(function(properties) {
 *     PropertySelect.attachTo(node, {
 *         properties: properties
 *     })
 * })
 */
define([
    'flight/lib/component',
    'util/component/attacher'
], function(defineComponent, attacher) {


    var HIDE_PROPERTIES = ['http://visallo.org/comment#entry'];

    return defineComponent(PropertySelect);

    function PropertySelect() {
        this.after('teardown', function() {
            this.attacher.teardown();
        });

        this.after('initialize', function() {
            if ('properties' in this.attr) {
                console.warn('Attribute `properties` no longer used. Use new filter attribute');
            }
            this.on('filterProperties', function() {
                //console.log(event.type, this.attacher);
            });

            const {
                filter = {},
                rollupCompound = true,
                focus,
                placeholder,
                onlySearchable,
                onlySortable,
                showAdminConcepts,
                clearable
            } = this.attr;

            if (onlySearchable === true) {
                filter.searchable = true;
            }
            if (showAdminConcepts === true) {
                filter.userVisible = undefined;
            }
            if (onlySortable === true) {
                filter.sortable = true
            }

            this.attacher = attacher()
                .node(this.node)
                .params({
                    filter: { ...filter, rollupCompound },
                    autofocus: focus === true,
                    placeholder,
                    clearable
                })
                .behavior({
                    onSelected: (attacher, property) => {
                        this.trigger('propertyselected', { property: property });
                    }
                })
                .path('components/ontology/PropertySelector');

            this.attacher.attach();
        });
    }
});
