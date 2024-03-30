# Compiling

To simply produce an executable jar file, just run the following in your IDE:

```
mvn clean package
```

To push a new VERSION to this maven repo to be used in external projects, use the following commands:
```
mvn clean package
mvn install:install-file -DgroupId=com.badskater0729.yamltranslator -DartifactId=YAMLTranslator -Dversion=<NEW-VERSION-HERE> -Dfile=./target/YAMLTranslator.jar -Dpackaging=jar -DlocalRepositoryPath=. -DcreateChecksum=true -DgeneratePom=true
```