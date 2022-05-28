import static org.apache.iceberg.expressions.Expressions.hour;
import static org.apache.iceberg.expressions.Expressions.month;

import org.apache.iceberg.*;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.spark.IcebergSpark;
import org.apache.iceberg.spark.SparkSchemaUtil;
import org.apache.iceberg.types.Types;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.*;
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException;
import org.apache.spark.sql.types.*;
import org.junit.Before;
import org.junit.Test;

import javax.xml.crypto.Data;
import java.util.Arrays;

import static org.apache.spark.sql.functions.*;
import static org.junit.Assert.*;

public class IcebergIngestionTest {
    static private String TEST_FILE_DIR_PATH = "src/main/resources/csvtests";
    private SparkSession spark;

    @Before
    public void setUp() throws Exception {
        SparkConf config = getSparkConfig();
        spark = SparkSession
                .builder()
                .appName("icebergTesting")
                .master("local")
                .config(config)
                .getOrCreate();
    }

    @Test
    public void testIcebergReadFromCSVSuccessful() {
        final String targetDBName = "local.db.individual";
        final String path = TEST_FILE_DIR_PATH + "/individual.csv";
        Dataset<Row> df = spark.read()
                .option("header", "true")
                .option("inferSchema", "true")
                .csv(path);

        df.writeTo(targetDBName).createOrReplace();
        df = spark.table(targetDBName);
        assertEquals(2, df.count());
    }

    @Test
    public void testLargeFileIngestionFromCSVSuccessful() {
        final String targetDBName = "local.db.bigfile";
        final String path = TEST_FILE_DIR_PATH + "/LargeData.csv";
        Dataset<Row> df = spark.read()
                .option("header", "true")
                .option("inferSchema", "true")
                .csv(path);

        df.writeTo(targetDBName).createOrReplace();
        df = spark.table(targetDBName);
        assertEquals(10000000, df.count());
    }

    @Test
    public void testIcebergReadFromCSVAddNewFieldInTheMiddleSuccessful() throws NoSuchTableException {
        final String targetDBName = "local.db.individualnewfield";
        final String hadoopTablePath = "spark-warehouse/db/individualnewfield";
        final String baseFilePath = TEST_FILE_DIR_PATH + "/individual.csv";
        Dataset<Row> df = spark.read()
                .option("header", "true")
                .option("inferSchema", "true")
                .csv(baseFilePath);
        df.writeTo(targetDBName).createOrReplace();
        df = spark.table(targetDBName);
        assertEquals(2, df.count());

        Table table = loadHadoopTable(hadoopTablePath);
        UpdateSchema updateSchema = table.updateSchema();

        // A new session read did the magic?
        spark = spark.newSession();

        // Add new field in the middle
        final String updatedFilePath = TEST_FILE_DIR_PATH + "/individual_new_field.csv";
        Dataset<Row> df2 = spark.read()
                .option("header", "true")
                .option("inferSchema", "true")
                .csv(updatedFilePath);

        // Update Existing Schema
        Arrays.stream(df2.schema().fields())
                .filter(x -> !table.schema().columns().stream().anyMatch(y -> x.name().equals(y.name())))
                .forEach(z -> updateSchema.addColumn(z.name(), Types.StringType.get()));
        updateSchema.commit();

        // In Dataframe add new column
        for (Types.NestedField field : table.schema().columns()) {
            if (Arrays.stream(df2.schema().fields()).anyMatch(y -> field.name().equals(y.name()))) {
                continue;
            }

            df2 = df2.withColumn(
                    field.name(),
                    lit(null).cast(SparkSchemaUtil.convert(table.schema().findType(field.name()))));
        }

        df2.writeTo(targetDBName).append();
        df2 = spark.table(targetDBName);
        df2.show();
        assertEquals(5, df2.count());
    }

    @Test
    public void testIcebergReadFromCSVOrderChangeSuccessful() throws NoSuchTableException {
        final String targetDBName = "local.db.individualorderchange";
        final String baseFilePath = TEST_FILE_DIR_PATH + "/individual.csv";
        Dataset<Row> df = spark.read()
                .option("header", "true")
                .option("inferSchema", "true")
                .csv(baseFilePath);
        df.writeTo(targetDBName).createOrReplace();
        df = spark.table(targetDBName);
        assertEquals(2, df.count());

        // Read another csv file with same fields but different order
        final String updatedFilePath = TEST_FILE_DIR_PATH + "/individual_order_change.csv";
        Dataset<Row> df2 = spark.read()
                .option("header", "true")
                .option("inferSchema", "true")
                .csv(updatedFilePath);

        df2.writeTo(targetDBName).append();
        df2 = spark.table(targetDBName);
        df2.show();
        assertEquals(4, df2.count());
    }

    @Test
    public void testIcebergReadFromCSVDeleteFieldSuccessful() throws NoSuchTableException {
        final String targetDBName = "local.db.individualdeletefield";
        final String hadoopTablePath = "spark-warehouse/db/individualdeletefield";
        final String baseFilePath = TEST_FILE_DIR_PATH + "/individual.csv";
        Dataset<Row> df = spark.read()
                .option("header", "true")
                .option("inferSchema", "true")
                .csv(baseFilePath);
        df.writeTo(targetDBName).createOrReplace();
        df = spark.table(targetDBName);
        assertEquals(2, df.count());
        Table table = loadHadoopTable(hadoopTablePath);
        UpdateSchema updateSchema = table.updateSchema();

        // Add new field in the middle
        final String updatedFilePath = TEST_FILE_DIR_PATH + "/individual_delete_field.csv";
        Dataset<Row> df2 = spark.read()
                .option("header", "true")
                .option("inferSchema", "true")
                .csv(updatedFilePath);

        // Update Existing Schema
        Arrays.stream(df2.schema().fields())
                .filter(x -> !table.schema().columns().stream().anyMatch(y -> x.name().equals(y.name())))
                .forEach(z -> updateSchema.addColumn(z.name(), Types.StringType.get()));
        updateSchema.commit();

        // In Dataframe add new column
        for (Types.NestedField field : table.schema().columns()) {
            if (Arrays.stream(df2.schema().fields()).anyMatch(y -> field.name().equals(y.name()))) {
                continue;
            }

            df2 = df2.withColumn(
                    field.name(),
                    lit(null).cast(SparkSchemaUtil.convert(table.schema().findType(field.name()))));
        }

        df2.writeTo(targetDBName).append();
        df2 = spark.table(targetDBName);
        df2.show();
        assertEquals(4, df2.count());
    }

    @Test
    public void testIcebergReadFromCSVWithAllStingTypeSuccessful() {
        final String targetDBName = "local.db.individualdatatype";
        final String path = TEST_FILE_DIR_PATH + "/individual_data_type.csv";
        final String hadoopTablePath = "spark-warehouse/db/individualdatatype";
        Dataset<Row> df = spark.read()
                .option("header", "true")
                .csv(path);

        df.writeTo(targetDBName).createOrReplace();
        df = spark.table(targetDBName);
        assertEquals(2, df.count());

        // Verify all types are string types
        StructType schema = df.schema();
        Arrays.stream(schema.fields()).forEach(f -> assertTrue(f.dataType() instanceof StringType));
        df.show();

        Table table = loadHadoopTable(hadoopTablePath);
        table.schema().columns().forEach(c -> assertTrue(c.type() instanceof Types.StringType));
    }

    @Test
    public void testIcebergReadFromCSVWithPartitionByDateSuccessful() throws NoSuchTableException {
        final String targetDBName = "spark-warehouse/db/testpartitiontype";
        Schema schema = new Schema(
                Types.NestedField.required(1, "level", Types.StringType.get()),
                Types.NestedField.required(2, "event_time", Types.TimestampType.withZone(), "EventType"),
                //Types.NestedField.required(2, "event_time", Types.StringType.get()),
                Types.NestedField.required(3, "message", Types.StringType.get()),
                Types.NestedField.optional(4, "call_stack", Types.ListType.ofRequired(5, Types.StringType.get()))
        );

        PartitionSpec spec = PartitionSpec.builderFor(schema)
                .hour("event_time")
                //.identity("level")
                .build();

       Table table = createOrReplaceHadoopTable(schema, spec, targetDBName);

        // Update Partition Spec
        table.updateSpec()
                .addField("level")
                .removeField(hour("event_time"))
                .commit();
    }

    @Test
    public void testPartitionSpec1() throws AnalysisException {
        final String file_location = TEST_FILE_DIR_PATH + "/individual_spike.csv";

        SparkConf config = getSparkConfig();
        spark = SparkSession
                .builder()
                .appName("icebergTesting")
                .master("local")
                .master("local")
                .config(config)
                .getOrCreate();

        StructType schema = DataTypes.createStructType(new StructField[] {
                DataTypes.createStructField("Col1", DataTypes.StringType, true),
                DataTypes.createStructField("Col2", DataTypes.StringType, true),
                DataTypes.createStructField("Col3", DataTypes.IntegerType, true),
                DataTypes.createStructField("Col4", DataTypes.StringType, true),

        });

        Dataset<Row> df = spark.read().format("iceberg")
                .option("header", "true")
                .option("inferSchema", "true")
                .schema(schema)
                .csv(file_location);

        IcebergSpark.registerBucketUDF(spark, "iceberg_bucket16", DataTypes.IntegerType, 2);

        Column col = df.col("Col3");
        df.sortWithinPartitions(expr("iceberg_bucket16(Col3)")).writeTo("local.db.small_partition_table").partitionedBy(bucket(2, col)).createOrReplace();

        df.show();

        df = spark.read().format("iceberg").load("local.db.small_partition_table");


        Dataset<Row> df3 = spark.sql("select * from local.db.small_partition_table where Col4=\"Redmond\"");



        df.show();
    }

    @Test
    public void testPartitionSpec2() throws AnalysisException {
        final String file_location = TEST_FILE_DIR_PATH + "/LargeData.csv";
        final String targetDBName = "spark-warehouse/db/largePartition";

        SparkConf config = getSparkConfig();
        spark = SparkSession
                .builder()
                .appName("icebergTesting")
                .master("local")
                .master("local")
                .config(config)
                .getOrCreate();

        StructType schema = DataTypes.createStructType(new StructField[] {
                DataTypes.createStructField("Col1", DataTypes.StringType, true),
                DataTypes.createStructField("Col2", DataTypes.StringType, true),
                DataTypes.createStructField("Col3", DataTypes.StringType, true),
                DataTypes.createStructField("Col4", DataTypes.IntegerType, true),

        });

        Dataset<Row> df = spark.read().format("iceberg")
                .option("header", "true")
                .option("inferSchema", "true")
                .schema(schema)
                .csv(file_location);

        IcebergSpark.registerBucketUDF(spark, "iceberg_bucket16", DataTypes.IntegerType, 20);

        Column col = df.col("Col4");
        df.sortWithinPartitions(expr("iceberg_bucket16(Col4)")).writeTo("local.db.large_partition_table").partitionedBy(bucket(20, col)).createOrReplace();

        df.show();

        df = spark.read().format("iceberg").load("local.db.large_partition_table");

        Dataset<Row> df3 = spark.sql("select * from local.db.large_partition_table where Col4=4601");

        Dataset<Row> df4 = spark.sql("select * from local.db.large_partition_table.snapshots");

        Dataset<Row> df5 = spark.sql("select * from local.db.large_partition_table.manifests");

        Dataset<Row> df6 = spark.sql("select * from local.db.large_partition_table.history");

       long snap_id = Long.parseLong("6492684780613891387");

        Dataset<Row> df7 = spark.read()
                .option("snapshot-id", snap_id)
                .format("iceberg")
                .load("local.db.large_partition_table");

        /*CALL catalog_name.system.cherrypick_snapshot('local.db.large_partition_table.history', 1);*/

    /*    df3.show();

        df4.show();

        df5.show();

        df6.show();*/

        df7.show();

    }

    // Iceberg schema data types: https://iceberg.apache.org/schemas/
    @Test
    public void testIceBergDifferentDataTypes() {
        final String targetDBName = "local.db.differentdatatypes";
        final String hadoopTablePath = "spark-warehouse/db/differentdatatypes";
        final String path = TEST_FILE_DIR_PATH + "/different_data_types.csv";
        Dataset<Row> df = spark.read()
                .option("header", "true")
                .csv(path);
        df.writeTo(targetDBName).createOrReplace();

        Dataset<Row> dfWithInfer = spark.read()
                .option("header", "true")
                .option("inferSchema", "true")
                .csv(path);

        StructField[] dfWithInferFields = dfWithInfer.schema().fields();

        String schemaString = SchemaParser.toJson(SparkSchemaUtil.convert(dfWithInfer.schema()));
        Table table = loadHadoopTable(hadoopTablePath);
        UpdateSchema updateSchema = table.updateSchema();
        Arrays.stream(df.schema().fields())
                .forEach(z -> updateSchema.updateColumnDoc(z.name(), Arrays.stream(dfWithInferFields).filter(f -> f.name().equalsIgnoreCase(z.name())).findFirst().get().dataType().json()));
        updateSchema.commit();
        table.refresh();

        df = spark.table(targetDBName);
        df.printSchema();
    }

    static private SparkConf getSparkConfig() {
        SparkConf config = new SparkConf();
        config.set("spark.sql.legacy.createHiveTableByDefault", "false");
        config.set("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
        config.set("spark.sql.catalog.local", "org.apache.iceberg.spark.SparkCatalog");
        config.set("spark.sql.catalog.local.type", "hadoop");
        config.set("spark.sql.catalog.local.warehouse", "spark-warehouse");
        return config;
    }

    private Table loadHadoopTable(String location) {
        HadoopTables tables = new HadoopTables(spark.sparkContext().hadoopConfiguration());
        return tables.load(location);
    }

    private Table createOrReplaceHadoopTable(Schema schema, PartitionSpec spec, String tableIdentifier) {
        HadoopTables tables = new HadoopTables(spark.sparkContext().hadoopConfiguration());
        tables.dropTable(tableIdentifier);
        return tables.create(schema, spec, tableIdentifier);
    }
}