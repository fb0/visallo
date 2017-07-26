define([
    'create-react-class',
    './util',
    'util/formatters'
], function(createReactClass, util, F) {
    'use strict';

    const StructuredIngestTextSection = createReactClass({
        getInitialState() {
            return { rows: null, total: null, error: false }
        },
        componentDidMount() {
            this.analyze();
        },
        render() {
            const { rows, error, total } = this.state;

            return (
                <div className="com-visallo-structuredFile-text-table">
                  <div className="buttons">
                    <button onClick={this.onClick} className="btn btn-default icon-with-description">
                        <div style={{backgroundImage: 'url(img/glyphicons_custom/extract-rows.png)'}} className="icon"></div>
                        {i18n('csv.file_import.mapping.button')}
                        <div className="description">{i18n('csv.file_import.mapping.button.description')}</div>
                    </button>
                  </div>
                  <div className="table">
                    { error ? 'Error Analyzing File' :
                      rows ? (
                          <table>
                            {rows.length && total ? (
                                <thead>
                                    <tr>
                                        <th style={{ fontWeight: 'normal', fontStyle: 'italic'}} 
                                            colSpan={rows[0].columns.length}>
                                            {i18n('csv.file_import.mapping.summary', rows.length, F.number.pretty(total))}
                                        </th>
                                    </tr>
                                </thead>
                            ) : null}
                            <tbody>
                                {rows.map((row, rowIndex) => (
                                    <tr key={rowIndex} className={row.isBlank ? 'isBlank' : ''}>
                                    {row.columns.map((c, colIndex) => (
                                        <td key={colIndex} title={c}>{row.isBlank ? '&nbsp' : c}</td>
                                    ))}
                                    </tr>
                                ))}
                            </tbody>
                          </table>
                      ) :
                      'Loading...'
                    }
                    </div>
                </div>
            )
        },
        onClick() {
            const { vertex } = this.props;

            require([
                'org/visallo/web/structuredingest/core/js/form',
                'org/visallo/web/structuredingest/core/templates/modal.hbs'
            ], (CSVMappingForm, template) => {
                const $modal = $(template({})).appendTo('#app');
                CSVMappingForm.attachTo($modal, { vertex });
            });
        },
        analyze() {
            util.analyze(this.props.vertex.id, { hasHeaderRow: false })
                .then(result => {
                    const { rows, total } = result;
                    this.setState({ error: false, rows, total })
                })
                .catch(error => {
                    console.error(error);
                    this.setState({ error: true })
                })
        }
    });

    return StructuredIngestTextSection;
});
