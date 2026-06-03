

// Set a default area of interest (AOI)
var defaultAoi = ee.FeatureCollection("FAO/GAUL/2015/level2")
  .filter(ee.Filter.eq('ADM2_NAME', 'Udham Singh Nagar'))
  .geometry();

// Center the map on the default AOI.
Map.centerObject(defaultAoi, 9);

// Get the map's drawing tools and hide them by default.
var drawingTools = Map.drawingTools();
drawingTools.setShown(false);

// Ensure any geometries from previous script runs are cleared.
while (drawingTools.layers().length() > 0) {
  var layer = drawingTools.layers().get(0);
  drawingTools.layers().remove(layer);
}


 * @param {ee.Image} image The input Sentinel-2 image.
 * @return {ee.Image} The cloud-masked image, scaled to reflectance.
 */
function maskS2clouds(image) {
  var scl = image.select('SCL');
  // Keep pixels that are not clouds (classes 3, 9, 10, 11).
  var mask = scl.neq(3).and(scl.neq(9)).and(scl.neq(10)).and(scl.neq(11));
  return image.updateMask(mask)
      .divide(10000) // Scale to reflectance
      .select("B.*")
      .copyProperties(image, ["system:time_start"]);
}

/**
 * Applies the À Trous Wavelet Transform (ATWT) to sharpen an image.
 * @param {ee.Image} image The input image.
 * @param {Array<string>} bandNames A list of band names to sharpen.
 * @param {number} numLevels The number of decomposition levels (typically 2-4).
 * @return {ee.Image} The sharpened image.
 */
function ATWT_sharpen(image, bandNames, numLevels) {
  var originalImage = image.select(bandNames);
  var waveletDetails = [];

  // Define the base B3 spline kernel matrix.
  var kernelMatrix = [
      [1, 4, 6, 4, 1],
      [4, 16, 24, 16, 4],
      [6, 24, 36, 24, 6],
      [4, 16, 24, 16, 4],
      [1, 4, 6, 4, 1]
  ];

  // Normalize the matrix using JavaScript before creating the ee.Kernel.
  var normalizedKernelMatrix = kernelMatrix.map(function(row) {
    return row.map(function(value) {
      return value / 256;
    });
  });

  var kernel = ee.Kernel.fixed(5, 5, normalizedKernelMatrix);

  // Decompose the image into wavelet layers.
  var previousApprox = originalImage;
  for (var i = 0; i < numLevels; i++) {
    var convolved = previousApprox.convolve(kernel);
    var detail = previousApprox.subtract(convolved);
    waveletDetails.push(detail);
    previousApprox = convolved;
  }

  // Reconstruct the image by adding all the detail layers back to the original.
  var sharpened = originalImage;
  for (var j = 0; j < waveletDetails.length; j++) {
    sharpened = sharpened.add(waveletDetails[j]);
  }

  // Overwrite the original bands with the new sharpened bands.
  return image.addBands(sharpened, null, true);
}


/**
 * Calculates and adds the Modified Normalized Difference Water Index (MNDWI).
 * @param {ee.Image} image The input image (should be sharpened).
 * @return {ee.Image} The image with an 'MNDWI' band added.
 */
function addMNDWI(image) {
  var mndwi = image.normalizedDifference(['B3', 'B11']).rename('MNDWI');
  return image.addBands(mndwi);
}

// Define the threshold for identifying water from the MNDWI.
var mndwiThreshold = 0.05;

// --- STEP 3: BUILD THE INTERACTIVE USER INTERFACE (UI) ---

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

// --- STEP 4: DEFINE THE CORE FUNCTIONS ---

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
  print('🔄 Application has been reset.');
}

function runAnalysis() {
  var aoi;
  if (drawingTools.layers().length() > 0 && drawingTools.layers().get(0).geometries().length() > 0) {
    aoi = drawingTools.layers().get(0).getEeObject();
    print('Using user-drawn AOI for analysis.');
  } else {
    aoi = defaultAoi;
    print('No custom AOI drawn. Using default location: Udham Singh Nagar.');
  }

  drawingTools.stop();
  while (drawingTools.layers().length() > 0) {
    var layer = drawingTools.layers().get(0);
    drawingTools.layers().remove(layer);
  }

  resultsPanel.clear();
  resultsPanel.add(ui.Label('🔄 Calculating... This may take a moment.', {fontWeight: 'bold', color: 'black'}));

  Map.layers().reset();
  Map.centerObject(aoi, 10);

  // --- Image Processing ---
  var preFloodStartDate = '2023-03-01';
  var preFloodEndDate = '2023-04-30';
  var bandsToSharpen = ['B3', 'B4', 'B8', 'B11']; // Green, Red, NIR, SWIR1

  var s2Collection = ee.ImageCollection('COPERNICUS/S2_SR_HARMONIZED')
      .filterBounds(aoi)
      .map(maskS2clouds);

  var duringFloodCollection = s2Collection.filterDate(duringFloodStart.getValue(), duringFloodEnd.getValue());
  var preFloodCollection = s2Collection.filterDate(preFloodStartDate, preFloodEndDate);

  if (duringFloodCollection.size().getInfo() === 0 || preFloodCollection.size().getInfo() === 0) {
    resultsPanel.clear();
    resultsPanel.add(ui.Label('⚠️ No satellite images found for the selected pre-flood or flood periods. Please try a different date range.', {color: 'red'}));
    return;
  }

  // **THE FIX**: Use median() to create a stable and deterministic composite image.
  // This replaces the non-deterministic qualityMosaic().
  var duringFloodComposite = duringFloodCollection.median();
  var preFloodComposite = preFloodCollection.median();
  
  // -- Apply ATWT Sharpening --
  print('Applying ATWT sharpening...');
  var duringFloodSharpened = ATWT_sharpen(duringFloodComposite, bandsToSharpen, 3);
  var preFloodSharpened = ATWT_sharpen(preFloodComposite, bandsToSharpen, 3);
  
  // Add MNDWI band to the SHARPENED images.
  var duringFloodImage = addMNDWI(duringFloodSharpened);
  var preFloodImage = addMNDWI(preFloodSharpened);

  // --- Logical Correction for Water Mapping ---
  var permanentWater = preFloodImage.select('MNDWI').gt(mndwiThreshold).selfMask().rename('permanent_water');
  var waterDuringFlood = duringFloodImage.select('MNDWI').gt(mndwiThreshold).selfMask();
  var totalWater = permanentWater.unmask(0).or(waterDuringFlood.unmask(0)).selfMask().rename('MNDWI');
  var floodedArea = totalWater.unmask(0).subtract(permanentWater.unmask(0)).eq(1).selfMask().rename('water');
  
  // --- Identify built-up areas. ---
  var worldCover = ee.ImageCollection("ESA/WorldCover/v100").first();
  var builtUp = worldCover.select('Map').eq(50).selfMask();
  var floodedBuiltup = floodedArea.and(builtUp).selfMask().rename('flooded_builtup');

  // --- Area Calculations ---
  var pixelArea = ee.Image.pixelArea();
  var aoiArea = aoi.area();

  var calculateArea = function(image, band) {
      return image.multiply(pixelArea).reduceRegion({
          reducer: ee.Reducer.sum(),
          geometry: aoi,
          scale: 10,
          maxPixels: 1e13
      }).get(band, 0);
  };

  var totalWaterArea = calculateArea(totalWater, 'MNDWI');
  var floodArea = calculateArea(floodedArea, 'water');
  var floodedBuiltupArea = calculateArea(floodedBuiltup, 'flooded_builtup');
  var totalBuiltupArea = calculateArea(builtUp, 'Map');

  // --- Evaluate results and display them on the panel ---
  ee.Dictionary({
    totalWater: totalWaterArea,
    flood: floodArea,
    floodedBuiltup: floodedBuiltupArea,
    totalBuiltup: totalBuiltupArea,
    aoiArea: aoiArea
  }).evaluate(function(results, error) {
    resultsPanel.clear();
    if (error) {
      print('Server-side error:', error);
      resultsPanel.add(ui.Label('⚠️ Computation Error:', {color: 'red', fontWeight: 'bold'}));
      resultsPanel.add(ui.Label('The AOI might be too large or there is no data.', {color: 'red'}));
    } else {
      var maxWaterPercent = ee.Number(results.totalWater).divide(results.aoiArea).multiply(100);
      var floodPercent = ee.Number(results.flood).divide(results.aoiArea).multiply(100);
      var floodedBuiltupPercent = ee.Number(results.totalBuiltup).gt(0) ? ee.Number(results.floodedBuiltup).divide(results.totalBuiltup).multiply(100) : ee.Number(0);

      var boxStyle = {backgroundColor: 'rgba(66, 165, 245, 0.3)', padding: '6px', margin: '5px 0 0 0', borderRadius: '4px'};
      var textStyle = {color: 'black'};

      resultsPanel.add(ui.Panel([ui.Label('Max Water Coverage (%): ' + maxWaterPercent.format('%.2f').getInfo(), textStyle)], null, boxStyle));
      resultsPanel.add(ui.Panel([ui.Label('Flooded Area (New Water, %): ' + floodPercent.format('%.2f').getInfo(), textStyle)], null, boxStyle));
      resultsPanel.add(ui.Panel([ui.Label('Flooded Built-up (% of Total): ' + floodedBuiltupPercent.format('%.2f').getInfo(), textStyle)], null, boxStyle));
    }
  });

  // --- Add final data layers to the map ---
  var visParams = {bands: ['B4', 'B3', 'B2'], min: 0.0, max: 0.3};
  Map.addLayer(duringFloodSharpened.clip(aoi), visParams, 'Sharpened True Color (During Flood)');
  Map.addLayer(builtUp.clip(aoi), {palette: ['#964B00']}, 'Built-up Area', false);
  Map.addLayer(permanentWater.clip(aoi), {palette: ['#ADD8E6']}, 'Permanent Water (Pre-Flood)', false);
  Map.addLayer(totalWater.clip(aoi), {palette: ['#0000FF']}, 'Total Water (During Flood)');
  Map.addLayer(floodedArea.clip(aoi), {palette: ['#FF0000']}, 'Flooded Area Only (Red)');
  Map.addLayer(floodedBuiltup.clip(aoi), {palette: ['#800080']}, 'Flooded Built-up (Purple)');
}
this is a code and there is some error in this help in solving those errors