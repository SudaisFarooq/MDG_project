
var defaultAoi = ee.FeatureCollection("FAO/GAUL/2015/level2")
  .filter(ee.Filter.eq('ADM2_NAME', 'Udham Singh Nagar'))
  .geometry();

Map.centerObject(defaultAoi, 9);

var drawingTools = Map.drawingTools();
drawingTools.setShown(false);

while (drawingTools.layers().length() > 0) {
  var layer = drawingTools.layers().get(0);
  drawingTools.layers().remove(layer);
}

function maskS2clouds(image) {
  var scl = image.select('SCL');
  var mask = scl.neq(3).and(scl.neq(9)).and(scl.neq(10)).and(scl.neq(11));
  return image.updateMask(mask)
      .divide(10000) 
      .select("B.*")
      .copyProperties(image, ["system:time_start"]);
}

function ATWT_sharpen(image, bandNames, numLevels) {
  var originalImage = image.select(bandNames);
  var waveletDetails = [];

  var kernelMatrix = [
      [1, 4, 6, 4, 1],
      [4, 16, 24, 16, 4],
      [6, 24, 36, 24, 6],
      [4, 16, 24, 16, 4],
      [1, 4, 6, 4, 1]
  ];

  var normalizedKernelMatrix = kernelMatrix.map(function(row) {
    return row.map(function(value) {
      return value / 256;
    });
  });

  var kernel = ee.Kernel.fixed(5, 5, normalizedKernelMatrix);

  var previousApprox = originalImage;
  for (var i = 0; i < numLevels; i++) {
    var convolved = previousApprox.convolve(kernel);
    var detail = previousApprox.subtract(convolved);
    waveletDetails.push(detail);
    previousApprox = convolved;
  }

  var sharpened = originalImage;
  for (var j = 0; j < waveletDetails.length; j++) {
    sharpened = sharpened.add(waveletDetails[j]);
  }

  return image.addBands(sharpened, null, true);
}

function addMNDWI(image) {
  var mndwi = image.normalizedDifference(['B3', 'B11']).rename('MNDWI');
  return image.addBands(mndwi);
}

var mndwiThreshold = 0.05;


var panel = ui.Panel({
  style: { width: '350px', padding: '10px', backgroundColor: 'rgba(255, 255, 255, 0.85)' }
});

var title = ui.Label({
  value: '💧 Flood Impact Analysis (ATWT Enhanced)',
  style: {fontSize: '22px', fontWeight: 'bold', color: '#0d47a1'}
});
panel.add(title);

panel.add(ui.Label({
  value: 'Default Location: Udham Singh Nagar',
  style: {fontSize: '12px', color: '#424242', margin: '4px 0 15px 0'}
}));

panel.add(ui.Label('Define Area of Interest (Optional):', {
  fontWeight: 'bold', color: '#1565c0', margin: '15px 0 0 0'
}));
panel.add(ui.Label('Draw a rectangle or run with the default location.', {fontSize: '11px'}));

panel.add(ui.Button({
  label: 'Draw Custom AOI on Map',
  onClick: function() {
    while (drawingTools.layers().length() > 0) {
      var layer = drawingTools.layers().get(0);
      drawingTools.layers().remove(layer);
    }
    drawingTools.setShape('rectangle');
    drawingTools.draw();
  },
  style: {stretch: 'horizontal'}
}));

panel.add(ui.Label('Enter Flood Period:', {
  fontWeight: 'bold', color: '#1565c0', margin: '15px 0 0 0'
}));

var duringFloodStart = ui.Textbox({placeholder: 'YYYY-MM-DD', value: '2023-07-01', style: {stretch: 'horizontal'}});
var duringFloodEnd = ui.Textbox({placeholder: 'YYYY-MM-DD', value: '2023-08-30', style: {stretch: 'horizontal'}});
var labelStyle = {margin: '0 0 0 4px', fontSize: '12px', color: 'black'};
var startPanel = ui.Panel([ui.Label('Start Date:', labelStyle), duringFloodStart], null, {stretch: 'horizontal'});
var endPanel = ui.Panel([ui.Label('End Date:', labelStyle), duringFloodEnd], null, {stretch: 'horizontal'});
panel.add(ui.Panel([startPanel, endPanel], ui.Panel.Layout.flow('horizontal'), {margin: '10px 0 0 0'}));

var runButton = ui.Button({
  label: 'Run Analysis',
  onClick: runAnalysis,
  style: {stretch: 'horizontal', backgroundColor: '#42a5f5', color: 'white', fontWeight: 'bold', margin: '20px 0 0 0'}
});
panel.add(runButton);

var resultsPanel = ui.Panel({style: {margin: '10px 0 0 0'}});
panel.add(resultsPanel);

var resetButton = ui.Button({
  label: 'Reset and Start Over',
  onClick: resetApp,
  style: {stretch: 'horizontal', margin: '10px 0 0 0', color: '#c62828', fontWeight: 'bold'}
});
panel.add(resetButton);

ui.root.insert(0, panel);

function resetApp() {
  Map.layers().reset();
  while (drawingTools.layers().length() > 0) {
    var layer = drawingTools.layers().get(0);
    drawingTools.layers().remove(layer);
  }
  resultsPanel.clear();
  duringFloodStart.setValue('2023-07-01');
  duringFloodEnd.setValue('2023-08-30');
  Map.centerObject(defaultAoi, 9);
}

function runAnalysis() {
  var aoi;
  var layers = drawingTools.layers();

  if (layers.length() > 0) {
    var layer = layers.get(0);
    aoi = layer.getEeObject(); 
    print('Using user-drawn AOI for analysis.');
  } else {
    aoi = defaultAoi;
    print('No custom AOI drawn. Using default location: Udham Singh Nagar.');
  }

  drawingTools.stop();
  
  while (drawingTools.layers().length() > 0) {
    var layerToRemove = drawingTools.layers().get(0);
    drawingTools.layers().remove(layerToRemove);
  }

  resultsPanel.clear();
  resultsPanel.add(ui.Label('🔄 Calculating... This may take a moment.', {fontWeight: 'bold', color: 'black'}));

  Map.layers().reset();
  Map.centerObject(aoi, 10);

  var preFloodStartDate = '2023-03-01';
  var preFloodEndDate = '2023-04-30';
  var bandsToSharpen = ['B3', 'B4', 'B8', 'B11']; 

  var s2Collection = ee.ImageCollection('COPERNICUS/S2_SR_HARMONIZED')
      .filterBounds(aoi)
      .map(maskS2clouds);

  var duringFloodCollection = s2Collection.filterDate(duringFloodStart.getValue(), duringFloodEnd.getValue());
  var preFloodCollection = s2Collection.filterDate(preFloodStartDate, preFloodEndDate);

  ee.Dictionary({
    duringSize: duringFloodCollection.size(),
    preSize: preFloodCollection.size()
  }).evaluate(function(sizes) {
    if (sizes.duringSize === 0 || sizes.preSize === 0) {
      resultsPanel.clear();
      resultsPanel.add(ui.Label('⚠️ No satellite images found for the selected periods. Try a different date range.', {color: 'red'}));
      return;
    }

    var duringFloodComposite = duringFloodCollection.median();
    var preFloodComposite = preFloodCollection.median();
    
    var duringFloodSharpened = ATWT_sharpen(duringFloodComposite, bandsToSharpen, 3);
    var preFloodSharpened = ATWT_sharpen(preFloodComposite, bandsToSharpen, 3);
    
    var duringFloodImage = addMNDWI(duringFloodSharpened);
    var preFloodImage = addMNDWI(preFloodSharpened);

    var permanentWater = preFloodImage.select('MNDWI').gt(mndwiThreshold).selfMask().rename('permanent_water');
    var waterDuringFlood = duringFloodImage.select('MNDWI').gt(mndwiThreshold).selfMask();
    var totalWater = permanentWater.unmask(0).or(waterDuringFlood.unmask(0)).selfMask().rename('MNDWI');
    var floodedArea = totalWater.unmask(0).subtract(permanentWater.unmask(0)).eq(1).selfMask().rename('water');
    
    var worldCover = ee.ImageCollection("ESA/WorldCover/v100").first();
    var builtUp = worldCover.select('Map').eq(50).selfMask();
    var floodedBuiltup = floodedArea.and(builtUp).selfMask().rename('flooded_builtup');

    var pixelArea = ee.Image.pixelArea();
    var aoiArea = aoi.area(10); // 10-meter error margin to prevent complex geometry crash

    var calculateArea = function(image, band) {
        return image.unmask(0).multiply(pixelArea).reduceRegion({
            reducer: ee.Reducer.sum(),
            geometry: aoi,
            scale: 10,
            maxPixels: 1e13
        }).getNumber(band); 
    };

    var totalWaterArea = calculateArea(totalWater, 'MNDWI');
    var floodArea = calculateArea(floodedArea, 'water');
    var floodedBuiltupArea = calculateArea(floodedBuiltup, 'flooded_builtup');
    var totalBuiltupArea = calculateArea(builtUp, 'Map');

    ee.Dictionary({
      totalWater: totalWaterArea,
      flood: floodArea,
      floodedBuiltup: floodedBuiltupArea,
      totalBuiltup: totalBuiltupArea,
      aoiArea: aoiArea
    }).evaluate(function(results, error) {
      resultsPanel.clear();
      if (error) {
        resultsPanel.add(ui.Label('⚠️ Computation Error:', {color: 'red', fontWeight: 'bold'}));
        resultsPanel.add(ui.Label(error, {color: 'red'}));
      } else {
        var maxWaterPercent = (results.totalWater / results.aoiArea) * 100;
        var floodPercent = (results.flood / results.aoiArea) * 100;
        var floodedBuiltupPercent = (results.totalBuiltup > 0) ? (results.floodedBuiltup / results.totalBuiltup) * 100 : 0;

        var boxStyle = {backgroundColor: 'rgba(66, 165, 245, 0.3)', padding: '6px', margin: '5px 0 0 0', borderRadius: '4px'};
        var textStyle = {color: 'black'};

        resultsPanel.add(ui.Panel([ui.Label('Max Water Coverage (%): ' + maxWaterPercent.toFixed(2), textStyle)], null, boxStyle));
        resultsPanel.add(ui.Panel([ui.Label('Flooded Area (New Water, %): ' + floodPercent.toFixed(2), textStyle)], null, boxStyle));
        resultsPanel.add(ui.Panel([ui.Label('Flooded Built-up (% of Total): ' + floodedBuiltupPercent.toFixed(2), textStyle)], null, boxStyle));
      }
    });

    var visParams = {bands: ['B4', 'B3', 'B2'], min: 0.0, max: 0.3};
    Map.addLayer(duringFloodSharpened.clip(aoi), visParams, 'Sharpened True Color (During Flood)');
    Map.addLayer(builtUp.clip(aoi), {palette: ['#964B00']}, 'Built-up Area', false);
    Map.addLayer(permanentWater.clip(aoi), {palette: ['#ADD8E6']}, 'Permanent Water (Pre-Flood)', false);
    Map.addLayer(totalWater.clip(aoi), {palette: ['#0000FF']}, 'Total Water (During Flood)');
    Map.addLayer(floodedArea.clip(aoi), {palette: ['#FF0000']}, 'Flooded Area Only (Red)');
    Map.addLayer(floodedBuiltup.clip(aoi), {palette: ['#800080']}, 'Flooded Built-up (Purple)');
  });
}