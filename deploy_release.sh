#!/bin/bash

# Change to the directory where the script is located
cd "$(dirname "$0")" || exit

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Check if Homebrew is installed
if ! command_exists brew; then
    echo "Homebrew is not installed. Please install it first."
    echo "You can install Homebrew by running:"
    echo '/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"'
    exit 1
fi

# Check for required commands
for cmd in mvn shasum md5; do
    if ! command_exists $cmd; then
        echo "$cmd is not installed. Attempting to install via Homebrew..."
        brew install $cmd
        if [ $? -ne 0 ]; then
            echo "Failed to install $cmd. Please install it manually."
            exit 1
        fi
    fi
done

# Ask user for JAR file location
read -p "Enter the full path to the JAR file: " jar_path
if [ ! -f "$jar_path" ]; then
    echo "Error: The specified JAR file does not exist."
    exit 1
fi

# Ask user for version
read -p "Enter the version number: " version

# Run Maven install command
mvn install:install-file \
    -DgroupId=com.badskater0729.yamltranslator \
    -DartifactId=YAMLTranslator \
    -Dversion="$version" \
    -Dfile="$jar_path" \
    -Dpackaging=jar \
    -DlocalRepositoryPath=. \
    -DcreateChecksum=true \
    -DgeneratePom=true

if [ $? -ne 0 ]; then
    echo "Maven install failed. Exiting."
    exit 1
fi

# Generate SHA1 and MD5 checksums
echo "Generating checksums..."
cd com/badskater0729/yamltranslator/YAMLTranslator/"$version"

for file in YAMLTranslator-"$version".{jar,pom}; do
    shasum -a 1 "$file" > "$file.sha1"
    md5 "$file" > "$file.md5"
done

echo "Deployment complete. Files are ready to be committed and pushed to the repository."
