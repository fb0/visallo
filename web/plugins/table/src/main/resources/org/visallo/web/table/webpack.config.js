var path = require('path');
var webpack = require('webpack');
var VisalloAmdExternals = [
 'classnames',
 'public/v1/api',
 'util/popovers/withPopover',
 'org/visallo/web/table/hbs/columnConfigPopover',
 'react',
 'create-react-class',
 'prop-types',
 'react-dom',
 'redux',
 'react-redux',
 'data/web-worker/store/selection/actions',
 'data/web-worker/store/product/selectors',
 'data/web-worker/store/ontology/selectors'
].map(path => ({ [path]: { amd: path }}));

module.exports = {
  entry: {
    card: './js/card/SavedSearchTableContainer.jsx'
  },
  output: {
    path: './dist',
    filename: 'Card.js',
    library: 'Card',
    libraryTarget: 'umd',
  },
  externals: VisalloAmdExternals,
  resolve: {
    extensions: ['', '.js', '.jsx', '.hbs']
  },
  module: {
    loaders: [
        {
            test: /\.jsx?$/,
            exclude: /(node_modules)/,
            loader: 'babel'
        }
    ]
  },
  devtool: 'source-map',
  plugins: [
    new webpack.optimize.UglifyJsPlugin({
        mangle: false,
        compress: {
            drop_debugger: false
        }
    })
  ]
};
