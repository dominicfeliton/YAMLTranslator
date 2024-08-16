package com.dominicfeliton.yamltranslator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.configuration.file.YamlConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.translate.TranslateClient;
import software.amazon.awssdk.services.translate.model.*;

public class YAMLTranslator {

	public static void main(String[] args) throws IOException {

		// Creds (Parse from file)
		String amazonAccessKey;
		String amazonSecretKey;
		String amazonRegion;
		
		// Other vars
		String inputLang;
		String originalYAMLDir;
		String outputYAMLDir;
		String originalYAML;
		String outputYAML;
		
		HashMap<String, String> replacementVals = new HashMap<>();

		Scanner scanner = new Scanner(System.in);

		/* Load settings YAML */
		URL defaultSettings = YAMLTranslator.class.getClassLoader().getResource("yt-settings.yml");
		File settingsFile = new File("./yt-settings.yml");
		if (!settingsFile.exists()) {
			copyFileUsingStream(defaultSettings.openConnection().getInputStream(), settingsFile);
		}
		
		YamlConfiguration settingsYaml = YamlConfiguration.loadConfiguration(settingsFile);
		Reader mainConfigStream = null;
		mainConfigStream = new InputStreamReader(defaultSettings.openConnection().getInputStream(), "UTF-8");
		settingsYaml.setDefaults(YamlConfiguration.loadConfiguration(mainConfigStream));
		settingsYaml.options().copyDefaults(true);
		
		settingsYaml.save("./yt-settings.yml");
		
		amazonAccessKey = settingsYaml.getString("amazonAccessKey");
		amazonSecretKey = settingsYaml.getString("amazonSecretKey");
		amazonRegion = settingsYaml.getString("amazonRegion");
		originalYAMLDir = settingsYaml.getString("originalYAMLDir");
		outputYAMLDir = settingsYaml.getString("outputYAMLDir");
		inputLang = settingsYaml.getString("inputLang");
		for (String eaKey : settingsYaml.getConfigurationSection("replacementValues").getKeys(false)) {
			replacementVals.put(eaKey, settingsYaml.get("replacementValues." + eaKey).toString());
		}

		/* Enter any missing vars */
		if (amazonAccessKey == null || amazonAccessKey.isEmpty()) {
			System.out.println("Enter Amazon Access Key: ");
			amazonAccessKey = scanner.nextLine();
		}

		if (amazonSecretKey == null || amazonSecretKey.isEmpty()) {
			System.out.println("Enter Amazon Secret Key: ");
			amazonSecretKey = scanner.nextLine();
		}

		if (amazonRegion == null || amazonRegion.isEmpty()) {
			System.out.println("Enter Amazon Region: ");
			amazonRegion = scanner.nextLine();
		}

		if (originalYAMLDir == null || originalYAMLDir.isEmpty()) {
			System.out.println("Enter parent directory of original YAML (include ending /): ");
			originalYAMLDir = scanner.nextLine();
		}

		if (outputYAMLDir == null || outputYAMLDir.isEmpty()) {
			System.out.println("Enter parent directory of output YAMLs (include ending /): ");
			outputYAMLDir = scanner.nextLine();
		}

		if (inputLang == null || inputLang.isEmpty()) {
			System.out.println("Enter language of original YAML: ");
			inputLang = scanner.nextLine();
		}

		/* Initialize AWS Creds + Translation Object */
		AwsBasicCredentials awsCreds = AwsBasicCredentials.create(
				amazonAccessKey, amazonSecretKey
		);

		TranslateTextResponse result;
		try (TranslateClient translate = TranslateClient.builder()
				.region(Region.of(amazonRegion))
				.credentialsProvider(StaticCredentialsProvider.create(awsCreds))
				.httpClientBuilder(UrlConnectionHttpClient.builder())
				.build()) {

			/* Get supported languages from AWS */
			ListLanguagesRequest langRequest = ListLanguagesRequest.builder().build();
			ListLanguagesResponse langResponse = translate.listLanguages(langRequest);
			List<Language> awsLangs = langResponse.languages();

			/* Convert supportedLangs to our own SupportedLang objs */
			ArrayList<String> supportedLangs = new ArrayList<String>();
			for (Language eaLang : awsLangs) {
				// Ignore auto
				if (eaLang.languageCode().equals("auto") || eaLang.languageName().equals("auto")) {
					continue;
				}
				supportedLangs.add(eaLang.languageCode());
			}

			/* Begin translating for all langs */
			for (String eaSupportedLang : supportedLangs) {
				// Don't translate the same language
				if (eaSupportedLang.equals(inputLang)) {continue;}

				// Init basic vars
				originalYAML = originalYAMLDir + "messages-" + inputLang + ".yml";
				outputYAML = outputYAMLDir + "messages-" + eaSupportedLang + ".yml";
				System.out.println("Input language is " + inputLang + ".");
				System.out.println("Output language is currently " + eaSupportedLang + ".");

				// Parse YAML into local HashMap
				HashMap<String, String> untranslated = new HashMap<String, String>();
				YamlConfiguration messagesConfig = YamlConfiguration.loadConfiguration(new File(originalYAML));
				YamlConfiguration newConfig = YamlConfiguration.loadConfiguration(new File(outputYAML));

				// Update existing target YAML, or create new one
				// Don't translate existing values
				if (new File(outputYAML).exists()) {
					System.out.println("Found existing file at output YAML path. \nParsing...");
					// Find new keys from original config
					for (String eaKey : messagesConfig.getConfigurationSection("Messages").getKeys(true)) {
						if (!newConfig.contains("Messages." + eaKey)) {
							untranslated.put(eaKey, messagesConfig.getString("Messages." + eaKey));
						}
					}
					// Find old unneeded keys from new config and delete them
					for (String eaKey : newConfig.getConfigurationSection("Messages").getKeys(true)) {
						if (!messagesConfig.contains("Messages." + eaKey)) {
							newConfig.set("Messages." + eaKey, null);
							newConfig.save(outputYAML);
							System.out.println("Deleted old key: " + eaKey);
						}
					}
				} else {
					/* Create new config */
					System.out.println("Creating new YAML...");
					newConfig.createSection("Messages");
					newConfig.save(new File(outputYAML));
					for (String eaKey : messagesConfig.getConfigurationSection("Messages").getKeys(true)) {
						untranslated.put(eaKey, messagesConfig.getString("Messages." + eaKey));
					}
				}

				// Successfully piped; begin translation
				for (Map.Entry<String, String> entry : untranslated.entrySet()) {
					String translatedLineName = entry.getKey();
					String translatedLine = "";
					String returnedText = entry.getValue();
					System.out.println("(Original) " + returnedText);

					// Regex to find placeholders like {0}, {1}, etc.
					Pattern pattern = Pattern.compile("\\{(\\d+)}");
					Matcher matcher = pattern.matcher(returnedText);

					// Wrap placeholders with <span translate="no">...</span>
					returnedText = matcher.replaceAll("<span translate=\"no\">$0</span>");
					//System.out.println("DEBUG REMOVE THIS: " + returnedText);

					/* Actual translation */
					if (!entry.getValue().isEmpty()) {
						// Create the translation request using the builder pattern
						TranslateTextRequest request = TranslateTextRequest.builder()
								.text(returnedText)
								.sourceLanguageCode(inputLang)
								.targetLanguageCode(eaSupportedLang)
								.build();

						// Execute the translation
						result = translate.translateText(request);
						translatedLine += result.translatedText();
					}

					// Factor in exclusions
					for (String eaKey : settingsYaml.getConfigurationSection("replacementValues").getKeys(false)) {
						translatedLine = translatedLine.replaceAll(eaKey, replacementVals.get(eaKey));
					}

					// Remove span translate=no around numbers with brackets
					translatedLine = translatedLine.replaceAll("<span translate=\"no\">(\\{\\d+})</span>", "$1");

					System.out.println("(Translated) " + translatedLine);

					// Translation done, write to new config file
					newConfig.set("Messages." + translatedLineName, translatedLine);
				}

				// Format check if already translated
				System.out.println("Final format check on " + eaSupportedLang + "...");

				for (String eaKey : newConfig.getConfigurationSection("Messages").getKeys(true)) {
					String line = newConfig.getString("Messages." + eaKey);

					for (String eaSettingsKey : settingsYaml.getConfigurationSection("replacementValues").getKeys(false)) {
						line = line.replaceAll(eaSettingsKey, replacementVals.get(eaSettingsKey));
					}

					newConfig.set("Messages." + eaKey, line);
				}

				// Final save
				newConfig.save(new File(outputYAML));
				System.out.println("== Done with " + eaSupportedLang + " ==");
			}
		}
		
		// Done!
		System.out.println("Finished! Results saved to \"" + outputYAMLDir + "\". \nExiting...");
		scanner.close();
		//System.exit(0);
	}
	
	private static void copyFileUsingStream(InputStream is, File dest) throws IOException {
        try (is; OutputStream os = new FileOutputStream(dest)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        }
	}
}
