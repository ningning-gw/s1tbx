/*
 * Copyright (C) 2010 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.beam.pixex;

import com.bc.ceres.binding.Property;
import com.bc.ceres.binding.ValidationException;
import com.bc.ceres.binding.Validator;
import org.esa.beam.dataio.placemark.PlacemarkIO;
import org.esa.beam.framework.dataio.ProductIO;
import org.esa.beam.framework.datamodel.GeoCoding;
import org.esa.beam.framework.datamodel.GeoPos;
import org.esa.beam.framework.datamodel.Mask;
import org.esa.beam.framework.datamodel.PinDescriptor;
import org.esa.beam.framework.datamodel.PixelPos;
import org.esa.beam.framework.datamodel.Placemark;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.RasterDataNode;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProducts;
import org.esa.beam.framework.gpf.annotations.TargetProperty;
import org.esa.beam.jai.ResolutionLevel;
import org.esa.beam.jai.VirtualBandOpImage;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.math.MathUtils;

import javax.media.jai.PlanarImage;
import javax.media.jai.operator.ConstantDescriptor;
import java.awt.Rectangle;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings({
                          "IOResourceOpenedButNotSafelyClosed", "MismatchedReadAndWriteOfArray",
                          "FieldCanBeLocal", "UnusedDeclaration"
                  })
@OperatorMetadata(
        alias = "PixEx",
        version = "1.0",
        authors = "Marco Peters, Thomas Storm",
        copyright = "(c) 2010 by Brockmann Consult",
        description = "Generates a CSV file from a given pixel location and source products.")
public class PixExOp extends Operator {

    public static final String RECURSIVE_INDICATOR = "**";

    @SourceProducts()
    private Product[] sourceProducts;

    @TargetProperty()
    private MeasurementReader measurements;

    @Parameter(
            description = "The paths to be scanned for input products. May point to a single file or a directory.\n" +
                          "If path ends with '**' the directory is scanned recursively.")
    private File[] inputPaths;

    @Parameter(description = "Specifies if bands are to be exported", defaultValue = "true")
    private Boolean exportBands;

    @Parameter(description = "Specifies if tie-points are to be exported", defaultValue = "true")
    private Boolean exportTiePoints;

    @Parameter(description = "Specifies if masks are to be exported", defaultValue = "true")
    private Boolean exportMasks;

    @Parameter(description = "The geo-coordinates", itemAlias = "coordinate")
    private Coordinate[] coordinates;

    @Parameter(description = "The acceptable time difference compared to the time given for a coordinate.\n" +
                             "The format is a number followed by (D)ay, (H)our or (M)inute.",
               defaultValue = "1D")
    private String timeDifference;

    @Parameter(description = "Path to a file containing geo-coordinates. BEAM's placemark files can be used.")
    private File coordinatesFile;

    @Parameter(description = "Side length of surrounding window (uneven)", defaultValue = "1",
               validator = WindowSizeValidator.class)
    private Integer windowSize;


    @Parameter(description = "The output directory.", notNull = true)
    private File outputDir;

    @Parameter(description = "The prefix is used to name the output files.", defaultValue = "pixEx")
    private String outputFilePrefix;

    @Parameter(description = "Band maths expression (optional)")
    private String expression;

    @Parameter(description = "If true, the expression result is exported, otherwise the expression is used as filter.",
               defaultValue = "true")
    private Boolean exportExpressionResult;

    private Map<String, String[]> rasterNamesMap = new HashMap<String, String[]>(37);
    private ProductValidator validator;
    private List<Coordinate> coordinateList;
    private boolean isTargetProductInitialized = false;
    private List<ProductDescription> productLocationList = new ArrayList<ProductDescription>();
    private Integer productId = 0;
    private int timeDelta = 0;
    private int calendarField = -1;
    private MeasurementWriter measurementWriter;


    int getTimeDelta() {
        return timeDelta;
    }

    int getCalendarField() {
        return calendarField;
    }

    Iterator<Measurement> getMeasurements() {
        return measurements;
    }

    @Override
    public void initialize() throws OperatorException {
        if (coordinatesFile == null && coordinates == null) {
            throw new OperatorException("No coordinates specified.");
        }
        if (outputDir != null && !outputDir.exists() && !outputDir.mkdirs()) {
            throw new OperatorException("Output directory does not exist and could not be created.");
        }
        coordinateList = initCoordinateList();
        parseTimeDelta(timeDifference);

        validator = new ProductValidator();
        measurementWriter = new MeasurementWriter(outputDir, outputFilePrefix, windowSize,
                                                  expression, exportExpressionResult);
        try {
            if (sourceProducts != null) {
                for (Product product : sourceProducts) {
                    extractMeasurements(product);
                }
            }
            if (inputPaths != null) {
                inputPaths = getParsedInputPaths(inputPaths);
                if (inputPaths.length == 0) {
                    getLogger().log(Level.WARNING, "No valid input path found.");
                }
                extractMeasurements(inputPaths);
            }
            if (!isTargetProductInitialized) {
                setDummyProduct();
            }
        } finally {
            measurementWriter.close();
        }

        try {
            measurements = new MeasurementReader(outputDir);
        } catch (IOException e) {
            throw new OperatorException("Could not create measurement reader.", e);
        }

    }

    @Override
    public void dispose() {
        try {
            measurements.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        super.dispose();
    }


    private void readMeasurement(Product product, Coordinate coordinate,
                                 int coordinateID, RenderedImage validMaskImage) throws IOException {
        PixelPos centerPos = product.getGeoCoding().getPixelPos(new GeoPos(coordinate.getLat(), coordinate.getLon()),
                                                                null);
        if (!product.containsPixel(centerPos)) {
            return;
        }
        final ProductData.UTC scanLineTime = ProductUtils.getScanLineTime(product, centerPos.y);
        if (coordinate.getDateTime() != null) {
            if (scanLineTime == null || !isPixelInTimeSpan(coordinate, timeDelta, calendarField, scanLineTime)) {
                return;
            }
        }
        int offset = MathUtils.floorInt(windowSize / 2);
        final int upperLeftX = MathUtils.floorInt(centerPos.x - offset);
        final int upperLeftY = MathUtils.floorInt(centerPos.y - offset);
        final Raster validData = validMaskImage.getData(new Rectangle(upperLeftX, upperLeftY, windowSize, windowSize));
        final String[] rasterNames = rasterNamesMap.get(product.getProductType());
        writeMeasurementRegion(coordinateID, coordinate.getName(), upperLeftX, upperLeftY, product,
                               rasterNames, validData);
    }

    private void writeMeasurementRegion(int coordinateID, String coordinateName, int upperLeftX, int upperLeftY,
                                        Product product, String[] rasterNames, Raster validData) throws IOException {
        final int productId = measurementWriter.registerProduct(product);
        final Number[] values = new Number[rasterNames.length];
        Arrays.fill(values, Double.NaN);
        final int numPixels = windowSize * windowSize;
        for (int n = 0; n < numPixels; n++) {
            int x = upperLeftX + n % windowSize;
            int y = upperLeftY + n / windowSize;

            final Measurement measure = createMeasurement(product, productId, coordinateID,
                                                          coordinateName, rasterNames, values, validData, x, y);
            measurementWriter.write(product, measure);
        }
    }

    public static Measurement createMeasurement(Product product, int productId,
                                                int coordinateID,
                                                String coordinateName, String[] rasterNames, Number[] values,
                                                Raster validData, int x, int y) throws IOException {
        for (int i = 0; i < rasterNames.length; i++) {
            RasterDataNode raster = product.getRasterDataNode(rasterNames[i]);
            if (raster != null && product.containsPixel(x, y)) {
                final int type = raster.getDataType();
                if (raster.isFloatingPointType()) {
                    double[] temp = new double[1];
                    raster.readPixels(x, y, 1, 1, temp);
                    values[i] = temp[0];
                } else {
                    int[] temp = new int[1];
                    raster.readPixels(x, y, 1, 1, temp);
                    if (raster instanceof Mask) {
                        values[i] = temp[0] == 0 ? 0 : 1; // normalize to 0 for false and 1 for true
                    } else {
                        if (raster.getDataType() == ProductData.TYPE_UINT32) {
                            values[i] = temp[0] & 0xffffL;
                        } else {
                            values[i] = temp[0];
                        }
                    }
                }
            }
        }
        final PixelPos pixelPos = new PixelPos(x + 0.5f, y + 0.5f);
        GeoPos currentGeoPos = product.getGeoCoding().getGeoPos(pixelPos, null);
        boolean isValid = validData.getSample(x, y, 0) != 0;
        final ProductData.UTC scanLineTime = ProductUtils.getScanLineTime(product, pixelPos.y);

        return new Measurement(coordinateID, coordinateName, productId,
                               pixelPos.x, pixelPos.y, scanLineTime, currentGeoPos, values,
                               isValid);
    }

    PlanarImage createValidMaskImage(Product product) {
        if (expression != null && product.isCompatibleBandArithmeticExpression(expression)) {
            return VirtualBandOpImage.create(expression, ProductData.TYPE_UINT8, 0,
                                             product, ResolutionLevel.MAXRES);
        } else {
            return ConstantDescriptor.create((float) product.getSceneRasterWidth(),
                                             (float) product.getSceneRasterHeight(),
                                             new Byte[]{-1}, null);
        }
    }

    private boolean isPixelInTimeSpan(Coordinate coordinate, int timeDiff, int calendarField,
                                      ProductData.UTC timeAtPixel) {
        final Calendar currentDate = timeAtPixel.getAsCalendar();

        final Calendar lowerTimeBound = (Calendar) currentDate.clone();
        lowerTimeBound.add(calendarField, -timeDiff);
        final Calendar upperTimeBound = (Calendar) currentDate.clone();
        upperTimeBound.add(calendarField, timeDiff);

        Calendar coordinateCal = ProductData.UTC.createCalendar();
        coordinateCal.setTime(coordinate.getDateTime());

        return lowerTimeBound.compareTo(coordinateCal) <= 0 && upperTimeBound.compareTo(coordinateCal) >= 0;
    }

    void parseTimeDelta(String timeDelta) {
        this.timeDelta = Integer.parseInt(timeDelta.substring(0, timeDelta.length() - 1));
        final String s = timeDelta.substring(timeDelta.length() - 1).toUpperCase();
        if ("D".equals(s)) {
            calendarField = Calendar.DATE;
        } else if ("H".equals(s)) {
            calendarField = Calendar.HOUR;
        } else if ("M".equals(s)) {
            calendarField = Calendar.MINUTE;
        } else {
            calendarField = Calendar.DATE;
        }

    }

    private List<Coordinate> initCoordinateList() {
        List<Coordinate> list = new ArrayList<Coordinate>();
        if (coordinatesFile != null) {
            list.addAll(extractCoordinates(coordinatesFile));
        }
        if (coordinates != null) {
            list.addAll(Arrays.asList(coordinates));
        }
        return list;
    }

    private List<Coordinate> extractCoordinates(File coordinatesFile) {
        final List<Coordinate> extractedCoordinates = new ArrayList<Coordinate>();
        FileReader fileReader = null;
        try {
            fileReader = new FileReader(coordinatesFile);
            final List<Placemark> pins = PlacemarkIO.readPlacemarks(fileReader,
                                                                    null, // no GeoCoding needed
                                                                    PinDescriptor.INSTANCE);
            for (Placemark pin : pins) {
                final GeoPos geoPos = pin.getGeoPos();
                if (geoPos != null) {
                    final Date dateTimeValue = (Date) pin.getFeature().getAttribute(Placemark.PROPERTY_NAME_DATETIME);
                    final Coordinate coordinate = new Coordinate(pin.getName(), geoPos.lat, geoPos.lon, dateTimeValue);
                    extractedCoordinates.add(coordinate);
                }
            }
        } catch (IOException cause) {
            throw new OperatorException(cause);
        } finally {
            if (fileReader != null) {
                try {
                    fileReader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return extractedCoordinates;
    }

    private void extractMeasurements(File[] files) {
        for (File file : files) {
            if (file.isDirectory()) {
                final File[] subFiles = file.listFiles();
                for (File subFile : subFiles) {
                    if (subFile.isFile()) {
                        extractMeasurements(subFile);
                    }
                }
            } else {
                extractMeasurements(file);
            }
        }
    }

    private void extractMeasurements(File file) {
        Product product = null;
        try {
            product = ProductIO.readProduct(file);
            extractMeasurements(product);
        } catch (IOException ignore) {
        } finally {
            if (product != null) {
                if (isTargetProductInitialized) {
                    product.dispose();
                } else {
                    setTargetProduct(product);
                    isTargetProductInitialized = true;
                }
            }
        }
    }

    private void extractMeasurements(Product product) {
        if (!validator.validate(product)) {
            return;
        }

        if (!rasterNamesMap.containsKey(product.getProductType())) {
            rasterNamesMap.put(product.getProductType(), getAllRasterNames(product));
        }

        final PlanarImage validMaskImage = createValidMaskImage(product);
        try {
            for (int i = 0, coordinateListSize = coordinateList.size(); i < coordinateListSize; i++) {
                Coordinate coordinate = coordinateList.get(i);
                try {
                    readMeasurement(product, coordinate, i + 1, validMaskImage);
                } catch (IOException e) {
                    getLogger().warning(e.getMessage());
                }
            }
        } finally {
            validMaskImage.dispose();
        }
    }

    private String[] getAllRasterNames(Product product) {
        final List<String> allRasterList = new ArrayList<String>();
        if (exportBands) {
            Collections.addAll(allRasterList, product.getBandNames());
        }
        if (exportTiePoints) {
            Collections.addAll(allRasterList, product.getTiePointGridNames());
        }
        if (exportMasks) {
            Collections.addAll(allRasterList, product.getMaskGroup().getNodeNames());
        }
        return allRasterList.toArray(new String[allRasterList.size()]);
    }

    private void setDummyProduct() {
        final Product product = new Product("dummy", "dummy", 2, 2);
        product.addBand("dummy", ProductData.TYPE_INT8);
        setTargetProduct(product);
    }

    static File[] getParsedInputPaths(File[] filePaths) {
        final ArrayList<File> directoryList = new ArrayList<File>();
        for (File file : filePaths) {
            String trimmedPath = file.getPath().trim();
            if (trimmedPath.endsWith(RECURSIVE_INDICATOR)) {
                trimmedPath = trimmedPath.substring(0, trimmedPath.lastIndexOf(RECURSIVE_INDICATOR));

                final File directory = new File(trimmedPath);
                collectDirectoriesRecursive(directory, directoryList);
            } else {
                directoryList.add(new File(trimmedPath));
            }
        }
        return directoryList.toArray(new File[directoryList.size()]);
    }

    private static void collectDirectoriesRecursive(File directory, ArrayList<File> directoryList) {
        if (directory.isDirectory()) {
            directoryList.add(directory);
            final File[] subDirs = directory.listFiles(new DirectoryFileFilter());
            for (File subDir : subDirs) {
                collectDirectoriesRecursive(subDir, directoryList);
            }
        }
    }

    private static class DirectoryFileFilter implements FileFilter {

        @Override
        public boolean accept(File pathname) {
            return pathname.isDirectory();
        }
    }

    private class ProductValidator {

        public boolean validate(Product product) {
            final Logger logger = getLogger();
            if (product == null) {
                return false;
            }
            final GeoCoding geoCoding = product.getGeoCoding();
            if (geoCoding == null) {
                final String msgPattern = "Product [%s] refused. Cause: Product is not geo-coded.";
                logger.warning(String.format(msgPattern, product.getFileLocation()));
                return false;
            }
            if (!geoCoding.canGetPixelPos()) {
                final String msgPattern = "Product [%s] refused. Cause: Pixel position can not be determined.";
                logger.warning(String.format(msgPattern, product.getFileLocation()));
                return false;
            }
            return true;
        }
    }

    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(PixExOp.class);
        }
    }

    public static class WindowSizeValidator implements Validator {

        @Override
        public void validateValue(Property property, Object value) throws ValidationException {
            if (((Integer) value) % 2 == 0) {
                throw new ValidationException("Value of squareSize must be uneven");
            }
        }
    }

}