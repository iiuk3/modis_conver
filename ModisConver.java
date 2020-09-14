import org.gdal.gdal.Band;
import org.gdal.gdal.Dataset;
import org.gdal.gdal.Driver;
import org.gdal.gdal.gdal;
import org.gdal.gdalconst.gdalconstConstants;
import org.gdal.osr.SpatialReference;

import java.util.*;

public class ModisConver {
    private String prefix;
    private boolean[] subset;
    private Integer resolution;
    private String outformat;
    private int epsg;
    private String wkt;
    private String resampl;

    private Dataset srcDs;
    private List<String> layers;
    private SpatialReference dstSrs;
    private String dstWkt;
    private int resampling;
    private Driver driver;

    private float errorThreshold = 0.125f;

    private int dstXSize;
    private int dstYSize;
    private double[] dstGT;

    private ModisConver(){};

    public ModisConver(String hdfname, String outPrefix) {
        this(hdfname, outPrefix, 3857);
    }

    public ModisConver(String hdfname, String outPrefix, Integer epsg){
        this(hdfname,outPrefix,null,null,"GTiff",epsg,null,"NEAREST_NEIGHBOR");
    }

    public ModisConver(String hdfname, String outPrefix,
                       boolean[] subset, Integer res,
                       String outformat, Integer epsg, String wkt,
                       String resampl){
        this.srcDs = gdal.Open(hdfname);
        this.layers = getSubDatasets(this.srcDs);
        this.prefix = outPrefix;
        this.resolution = res;
        if (null != epsg){
            this.dstSrs = new SpatialReference();
            this.dstSrs.ImportFromEPSG(epsg);
            this.dstWkt = this.dstSrs.ExportToWkt();
        }else if (null != wkt && !wkt.isEmpty()){
            this.dstWkt = wkt;
        }else {
            throw new RuntimeException("你必须指定其中一个参数：`epsg` 或者 `wkt`");
        }
        this.resampling = getResampling(resampl);
        this.subset = subset;
        if (null == outformat || outformat.isEmpty()){
            outformat = "GTiff";
        }
        this.driver = gdal.GetDriverByName(outformat);
        if (null == this.driver){
            throw new RuntimeException("输出格式不支持，请检查GDAL环境或检查`outformat`参数是否拼写错误");
        }
    }

    public void run(){
        this.createWarped(this.layers.get(0));
        int n = 0;
        if (null == this.subset){
            this.subset = new boolean[this.layers.size()];
            for (int i = 0; i < this.layers.size(); i++) {
                this.subset[i] = true;
            }
        }
        for (boolean b : this.subset) {
            if (b) {
                this.reprojectOne(this.layers.get(n));
                n = ++n;
                if (n > this.layers.size()){
                    break;
                }
            }
        }
    }


    private void reprojectOne(String l) {
        Dataset lSrcDs = gdal.Open(l);
        Band band = lSrcDs.GetRasterBand(1);
        String fillValue = lSrcDs.GetMetadataItem("_FillValue");
        if (null == fillValue || fillValue.isEmpty()){
            Double[] nodata = new Double[1];
            band.GetNoDataValue(nodata);
            if (null != nodata[0]){
                fillValue = String.valueOf(nodata[0]);
            }
        }
        int dataType = band.getDataType();
        String[] split = l.split(":");
        String outName;
        String lName = split[split.length - 1];
        if (null != this.prefix && !this.prefix.isEmpty()){
            outName = String.format("%s_%s.tif", this.prefix, lName);
        }else {
            outName = String.format("%s.tif",lName);
        }
        Dataset dstDs;
        try {
            dstDs = this.driver.Create(outName, this.dstXSize, this.dstYSize, 1, dataType);
        }catch (Exception e){
            throw new RuntimeException("无法创建数据集",e);
        }
        dstDs.SetProjection(this.dstWkt);
        dstDs.SetGeoTransform(this.dstGT);
        if (null != fillValue && !fillValue.isEmpty()){
            dstDs.GetRasterBand(1).SetNoDataValue(Double.parseDouble(fillValue));
            dstDs.GetRasterBand(1).Fill(Double.parseDouble(fillValue));
        }
        try {
            gdal.ReprojectImage(lSrcDs, dstDs, lSrcDs.GetProjection(),
                    this.dstWkt, this.resampling, 0, this.errorThreshold);
        }catch (Exception e){
            throw new RuntimeException(String.format("Not possible to reproject dataset %s", l),e);
        }
        dstDs.SetMetadata(lSrcDs.GetMetadata_Dict());
        dstDs.delete();
        lSrcDs.delete();
    }

    private void createWarped(String raster){
        Dataset src = gdal.Open(raster);
        Dataset tmpDs = gdal.AutoCreateWarpedVRT(src, src.GetProjection(),
                this.dstWkt, this.resampling,
                this.errorThreshold);
        if (null == this.resolution || this.resolution == 0){
            this.dstXSize = tmpDs.GetRasterXSize();
            this.dstYSize = tmpDs.GetRasterYSize();
            this.dstGT = tmpDs.GetGeoTransform();
        }else {
            double[][] bbox = this.boundingBox(tmpDs);
            this.dstXSize = this.calculateRes(bbox[0][0],bbox[1][0],this.resolution);
            this.dstYSize = this.calculateRes(bbox[0][1],bbox[1][1],this.resolution);
            if (this.dstXSize == 0){
                throw new RuntimeException("X大小的像素为0无效，可能是分辨率`resolution`有问题");
            }else if (this.dstYSize == 0){
                throw new RuntimeException("Y大小的像素为0无效，可能是分辨率`resolution`有问题");
            }
            this.dstGT = new double[]{bbox[0][0], this.resolution, 0, bbox[1][1], 0, -this.resolution};
        }
        tmpDs.delete();
        src.delete();
    }

    private int calculateRes(double minn, double maxx, Integer res) {

        return (int) Math.round((maxx - minn)/ res);
    }

    private double[][] boundingBox(Dataset src) {
        double[] srcGT = src.GetGeoTransform();
        double[][] srcBboxCells = {
                {0, 0},
                {0, src.GetRasterYSize()},
                {src.GetRasterXSize(), 0},
                {src.GetRasterXSize(), src.GetRasterYSize()}
        };
        List<Double> geoPtsX = new ArrayList<>();
        List<Double> geoPtsY = new ArrayList<>();
        for (int i = 0; i < srcBboxCells.length; i++){
            double x = srcBboxCells[i][0];
            double y = srcBboxCells[i][1];
            double x2 = srcGT[0] + srcGT[1] * x + srcGT[2] * y;
            double y2 = srcGT[3] + srcGT[4] * x + srcGT[5] * y;
            geoPtsX.add(x2);
            geoPtsY.add(y2);
        }
        Double xMin = geoPtsX.stream().min(Comparator.comparingDouble(Double::doubleValue)).get();
        Double xMax = geoPtsX.stream().max(Comparator.comparingDouble(Double::doubleValue)).get();
        Double yMin = geoPtsY.stream().min(Comparator.comparingDouble(Double::doubleValue)).get();
        Double yMax = geoPtsY.stream().max(Comparator.comparingDouble(Double::doubleValue)).get();
        double[][] box= {
                {xMin,yMin},
                {xMax,yMax}
        };
        return box;
    }

    private List<String> getSubDatasets(Dataset dataset){
        Hashtable<String,String> subdatasets = dataset.GetMetadata_Dict("SUBDATASETS");
        if (null == subdatasets){
            return null;
        }
        List<String> sd = new ArrayList<>();

        for (int i = 0; i < subdatasets.size(); i++) {
            String sub = subdatasets.get(String.format("SUBDATASET_%s_NAME", i));
            if (null != sub){
                sd.add(sub);
            }
        }
        return sd;
    }

    /**
     *
     * @param resampl
     * @return
     */
    private int getResampling(String resampl){
        switch (String.valueOf(resampl).toUpperCase()){
            case "AVERAGE" : return gdalconstConstants.GRA_Average;
            case "BILINEAR":
            case "BICUBIC" :
                return gdalconstConstants.GRA_Bilinear;
            case "LANCZOS" : return gdalconstConstants.GRA_Lanczos;
            case "MODE"    : return gdalconstConstants.GRA_Mode;
            case "CUBIC_CONVOLUTION" :
            case "CUBIC" : return gdalconstConstants.GRA_Cubic;
            case "CUBIC_SPLINE" : return gdalconstConstants.GRA_CubicSpline;
            case "NEAREST_NEIGHBOR" :
            default:return gdalconstConstants.GRA_NearestNeighbour;
        }
    }


}
