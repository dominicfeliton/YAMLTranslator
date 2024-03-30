package com.badskater0729.yamltranslator;

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
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.configuration.file.YamlConfiguration;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.translate.AmazonTranslate;
import com.amazonaws.services.translate.AmazonTranslateClient;
import com.amazonaws.services.translate.model.Language;
import com.amazonaws.services.translate.model.ListLanguagesRequest;
import com.amazonaws.services.translate.model.TranslateTextRequest;
import com.amazonaws.services.translate.model.TranslateTextResult;

public class YAMLTranslator {

	public static void main(String[] args) throws IOException {

		// Creds (Parse from file)
		String amazonAccessKey = "";
		String amazonSecretKey = "";
		String amazonRegion = "";
		
		// Other vars
		String inputLang = "";
		String originalYAMLDir = "";
		String outputYAMLDir = "";
		String originalYAML = "";
		String outputYAML = "";
		
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
		
		/* Initialize AWS Creds + Translation Object */
		BasicAWSCredentials awsCreds = new BasicAWSCredentials(amazonAccessKey, amazonSecretKey);
		AWSStaticCredentialsProvider credsProvider = new AWSStaticCredentialsProvider(awsCreds);
		AmazonTranslate translate = AmazonTranslateClient.builder()
				.withCredentials(credsProvider)
				.withRegion(amazonRegion).build();
		
		/* Get supported languages from AWS */
		ListLanguagesRequest langRequest = new ListLanguagesRequest().withRequestCredentialsProvider(credsProvider);
		List<Language> awsLangs = translate.listLanguages(langRequest).getLanguages();
		
		/* Convert supportedLangs to our own SupportedLang objs */
		ArrayList<String> supportedLangs = new ArrayList<String>();
		for (Language eaLang : awsLangs) {
			// Ignore auto
			if (eaLang.getLanguageCode().equals("auto") || eaLang.getLanguageName().equals("auto")) {
				continue;
			}
			supportedLangs.add(eaLang.getLanguageCode());
		}
		
		/* Enter any missing vars */
		if (amazonAccessKey.equals("")) {
			System.out.println("Enter Amazon Access Key: ");
			amazonAccessKey = scanner.nextLine().toString();
		}

		if (amazonSecretKey.equals("")) {
			System.out.println("Enter Amazon Secret Key: ");
			amazonSecretKey = scanner.nextLine().toString();
		}
		
		if (amazonRegion.equals("")) {
			System.out.println("Enter Amazon Region: ");
			amazonRegion = scanner.nextLine().toString();
		}

		if (originalYAMLDir.equals("")) {
			System.out.println("Enter parent directory of original YAML (include ending /): ");
			originalYAMLDir = scanner.nextLine().toString();
		}

		if (outputYAMLDir.equals("")) {
			System.out.println("Enter parent directory of output YAMLs (include ending /): ");
			outputYAMLDir = scanner.nextLine().toString();
		}
		
		if (inputLang.equals("")) {
			System.out.println("Enter language of original YAML: ");
			inputLang = scanner.nextLine().toString();
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
				Pattern pattern = Pattern.compile("\\{(\\d+)\\}");
				Matcher matcher = pattern.matcher(returnedText);

				// Wrap placeholders with <span translate="no">...</span>
				returnedText = matcher.replaceAll("<span translate=\"no\">$0</span>");
				//System.out.println("DEBUG REMOVE THIS: " + returnedText);

				/* Actual translation */
				if (entry.getValue().length() > 0) {
					TranslateTextRequest request = new TranslateTextRequest().withText(returnedText)
							.withSourceLanguageCode(inputLang).withTargetLanguageCode(eaSupportedLang);
					TranslateTextResult result = translate.translateText(request);
					translatedLine += result.getTranslatedText();
				}

				// Factor in exclusions
				for (String eaKey : settingsYaml.getConfigurationSection("replacementValues").getKeys(false)) {
					translatedLine = translatedLine.replaceAll(eaKey, replacementVals.get(eaKey).toString());
				}

				// Remove span translate=no around numbers with brackets
				translatedLine = translatedLine.replaceAll("<span translate=\"no\">(\\{\\d+\\})</span>", "$1");

				System.out.println("(Translated) " + translatedLine);

				// Translation done, write to new config file
				newConfig.set("Messages." + translatedLineName, translatedLine);
			}

			// Format check if already translated
			System.out.println("Final format check on " + eaSupportedLang + "...");

			for (String eaKey : newConfig.getConfigurationSection("Messages").getKeys(true)) {
				String line = newConfig.getString("Messages." + eaKey);

				for (String eaSettingsKey : settingsYaml.getConfigurationSection("replacementValues").getKeys(false)) {
					line = line.replaceAll(eaSettingsKey, replacementVals.get(eaSettingsKey).toString());
				}

				newConfig.set("Messages." + eaKey, line);
			}

			// Final save
			newConfig.save(new File(outputYAML));
			System.out.println("== Done with " + eaSupportedLang + " ==");
		}
		
		// Done!
		System.out.println("Finished! Results saved to \"" + outputYAMLDir + "\". \nExiting...");
		scanner.close();
		//System.exit(0);
	}
	
	private static void copyFileUsingStream(InputStream is, File dest) throws IOException {
	    OutputStream os = null;
	    try {
	        os = new FileOutputStream(dest);
	        byte[] buffer = new byte[1024];
	        int length;
	        while ((length = is.read(buffer)) > 0) {
	            os.write(buffer, 0, length);
	        }
	    } finally {
	        is.close();
	        os.close();
	    }
	}
}
