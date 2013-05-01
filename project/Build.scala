import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "CryoVault"
    val appVersion      = "1.0-SNAPSHOT"

    val appDependencies = Seq(
      "commons-io"				% "commons-io"		% "2.4",
      "org.scala-tools.sbinary"	% "sbinary_2.9.0"	% "0.4.0",
      "joda-time"				% "joda-time"		% "2.1"
      //"org.apache.httpcomponents" % "httpcore"		% "4.1.2",
      //"org.apache.httpcomponents" %	"httpclient"	% "4.1.2"
      //Requires Apache Commons (Codec, HTTP Client, and Logging) third-party packages, which are included in the third-party directory of the SDK.
    )

    val main = PlayProject(appName, appVersion, appDependencies, mainLang = SCALA).settings(
      // Add your own project settings here      
    )

}


/*
			<groupId>com.typesafe</groupId>
			<artifactId>config</artifactId>
			<version>0.5.0</version>
		</dependency>
		<dependency>
			<groupId>commons-io</groupId>
			<artifactId>commons-io</artifactId>
			<version>2.4</version>
		</dependency>
		<dependency>
			<groupId>org.scala-tools.sbinary</groupId>
			<artifactId>sbinary_2.9.0</artifactId>
			<version>0.4.0</version>
*/