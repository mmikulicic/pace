package test

import org.specs2.mutable._

import afm._
import afm.Watch._
import resource._
import java.io._
import scala.sys.runtime


object DbSpec extends Specification {

  def multiPass[A](featuresFile: String, sortedFeaturesFile: String, extractor: FeatureExtractor[A]) = {
    val feature = new MongoFeatureExtractor(extractor, featuresFile)
    feature.run

    val sorter = new Sorter(featuresFile, sortedFeaturesFile)
    sorter.run

    val lines = sorter.lines

    //val runner = new MongoStreamDetector("n", Some(lines))
    //val runner = new MongoExternallySorted(sortedFeaturesFile)
    val runner = new PrefetchingMongoExternallySorted(sortedFeaturesFile, Some(lines))
    //val runner = new ParalellFetchMongoExternallySorted(sortedFeaturesFile, Some(lines))
    //val runner = new CmdlineMongoExternallySorted(sortedFeaturesFile, Some(lines))
    runner.run
  }

  def simhash(featuresFile: String, sortedFeaturesFile: String) = {
    var last = (0.0, 0.0, 0)

    val collector = new MongoDBCollector("candidates")
      
    for(i <- 0 until 8)  {
      val extractor = new FieldFeatureExtractor(StringFieldDef("lastName", NullDistanceAlgo())) with SimhashValueExtractor {
        def step = i
      }

      val feature = new MongoFeatureExtractor(extractor, featuresFile.format(i))
      feature.run
      
      val sorter = new Sorter(featuresFile.format(i), sortedFeaturesFile.format(i))
      sorter.run
      
      val lines = sorter.lines
      
      val runner = new PrefetchingMongoExternallySorted(sortedFeaturesFile.format(i), Some(lines), existingCollector=Some(collector))
      val (precision, recall, time) = runner.run
      last = (precision, recall, last._3 + time)
    }

    last 
  }

  "pace" should {
    "rule" in {

      val ((precision, recall, candidates), time) = timeTook {
        println("running")
        Model.algo match {
          case "singleField" => {
            val runner = new MongoStreamDetector(Model.sortOn)
            runner.run
          }
          case "mergedSimhash" => {
            val features = new FieldFeatureExtractor(StringFieldDef("lastName", NullDistanceAlgo())) with RotatedSimhashValueExtractor
            multiPass("/tmp/simhash.txt", "/tmp/simhash.sorted", features)
          }
          case "simhash" => {
            simhash("/tmp/simhash-%s.txt", "/tmp/simhash-%s.sorted")
          }
          case "ngram" => {
            val features = new FieldFeatureExtractor(StringFieldDef("lastName", NullDistanceAlgo())) with NGramValueExtractor
            multiPass("/tmp/ngrams.txt", "/tmp/ngrams.sorted", features)
          }
        }
      }

      val size = Model.limit match {
        case Some(x) => x
        case None => Model.mongoDb("people").count
      }
      val cores = Model.cores.getOrElse(runtime.availableProcessors)

      val reportFileName = Model.conf.getString("pace.reportFile").getOrElse("/tmp/pace.csv")
      val exists = new File(reportFileName).exists

      for(report <- managed(new PrintWriter(new FileWriter(reportFileName , true)))) {
        if(!exists)
          report.println("size,window, cores, threshold,time,precision,recall,candidates")
        report.println((size, Model.windowSize, cores, Model.threshold, time/1000, precision, recall, candidates).productIterator.map(_.toString).mkString(","))
      }

      "test" must startWith("test")
    }
  }
}
