define([
    'create-react-class',
    'prop-types',
    './ConceptSelector',
    '../Alert'
], function(createReactClass, PropTypes, ConceptsSelector, Alert) {

    const RelationshipForm = createReactClass({
        propTypes: {
        },
        getInitialState() {
            return {}
        },
        getValue() {
            const { displayName } = this.state;
            const { displayName: defaultValue } = this.props;
            return _.isString(displayName) ? displayName : defaultValue;
        },
        render() {
            const value = this.getValue();
            const disabled = _.isEmpty(value);
            return (
                <div>
                    { this.props.error ? (<Alert error={this.props.error} />) : null }
                    <ConceptsSelector
                        value={_.isString(this.state.sourceConcept) ? this.state.sourceConcept : this.props.sourceConcept}
                        placeholder="From Concept"
                        filter={{conceptId: this.props.sourceConcept, showAncestors: true }}
                        creatable={false}
                        clearable={false}
                        onSelected={this.onSourceConceptSelected} />

                    <input type="text"
                        placeholder="Display Name"
                        onChange={this.onDisplayNameChange}
                        value={value} />

                    <ConceptsSelector
                        value={_.isString(this.state.targetConcept) ? this.state.targetConcept : this.props.targetConcept}
                        filter={{conceptId: this.props.targetConcept, showAncestors: true }}
                        placeholder="To Concept"
                        clearable={false}
                        creatable={false}
                        onSelected={this.onTargetConceptSelected} />

                    <div style={{textAlign: 'right'}}>
                    <button
                        onClick={this.props.onCancel}
                        className="btn btn-link btn-small"
                        style={{ width: 'auto', marginBottom: '1em'}}>Cancel</button>
                    <button
                        disabled={disabled}
                        onClick={this.onCreate}
                        className="btn btn-small btn-primary"
                        style={{ width: 'auto', marginBottom: '1em'}}>{
                            disabled ? 'Create' : `Create "${value}"`
                        }</button>
                    </div>
                </div>
            )
        },
        onDisplayNameChange(event) {
            this.setState({ displayName: event.target.value });
        },
        onSourceConceptSelected(concept) {
            this.setState({ sourceConcept: concept })
        },
        onTargetConceptSelected(concept) {
            this.setState({ targetConcept: concept })
        },
        onCreate() {
            this.props.onCreate({
                sourceIris: [this.state.sourceConcept || this.props.sourceConcept],
                targetIris: [this.state.targetConcept || this.props.targetConcept],
                displayName: this.getValue()
            })
        }
    });

    return RelationshipForm;
});

