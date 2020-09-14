# modis_conver
使用java+gdal将modis产品格式转换为其他格式（如geotiff）。代码参考pyModis

# 使用方法
1.在主机上安装gdal环境

2.在项目中导入gdal的jar包

3.代码示例

```java
 ModisConver modisConver = new ModisConver("Y:\\mod09a1\\2020.06.01\\MOD09A1.A2020153.h15v01.006.2020162062117.hdf","Y:\\mod09a1\\test\\test");
 modisConver.run();
```

