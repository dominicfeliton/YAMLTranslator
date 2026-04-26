#!/bin/bash

# Change to the directory where the script is located
cd "$(dirname "$0")" || exit

GROUP_ID="com.dominicfeliton.yamltranslator"
GROUP_PATH="com/dominicfeliton/yamltranslator"
ARTIFACT_ID="YAMLTranslator"
JAR_NAME="YAMLTranslator.jar"
SOURCE_BRANCH="${SOURCE_BRANCH:-${MASTER_BRANCH:-main}}"
SOURCE_WORKTREE_DIR="${SOURCE_WORKTREE_DIR:-${MASTER_WORKTREE_DIR:-../YAMLTranslator-main}}"
PUBLISH_VERSION="${PUBLISH_VERSION:-${VERSION:-${1:-}}}"

if [ "$#" -gt 1 ]; then
    echo "Usage: $0 [publish-version]"
    exit 1
fi

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

install_missing_command() {
    local cmd="$1"
    local package="${2:-$1}"

    if command_exists "$cmd"; then
        return
    fi

    echo "$cmd is not installed. Attempting to install..."
    if [ "$OS" == "macOS" ]; then
        $INSTALL_CMD "$package"
    elif [ "$OS" == "Linux" ]; then
        $INSTALL_CMD "$package"
    fi

    if [ $? -ne 0 ]; then
        echo "Failed to install $cmd. Please install it manually."
        exit 1
    fi
}

prepare_source_worktree() {
    if [ -e "$SOURCE_WORKTREE_DIR" ]; then
        if [ ! -d "$SOURCE_WORKTREE_DIR/.git" ] && [ ! -f "$SOURCE_WORKTREE_DIR/.git" ]; then
            echo "Error: $SOURCE_WORKTREE_DIR exists but is not a git worktree."
            exit 1
        fi
    else
        echo "Creating $SOURCE_BRANCH worktree at $SOURCE_WORKTREE_DIR..."
        git fetch origin "$SOURCE_BRANCH"

        if git show-ref --verify --quiet "refs/heads/$SOURCE_BRANCH"; then
            git worktree add "$SOURCE_WORKTREE_DIR" "$SOURCE_BRANCH"
        else
            git worktree add -b "$SOURCE_BRANCH" "$SOURCE_WORKTREE_DIR" "origin/$SOURCE_BRANCH"
        fi
    fi

    (
        cd "$SOURCE_WORKTREE_DIR" || exit 1

        current_branch=$(git branch --show-current)
        if [ "$current_branch" != "$SOURCE_BRANCH" ]; then
            echo "Error: $SOURCE_WORKTREE_DIR is on '$current_branch', not '$SOURCE_BRANCH'."
            exit 1
        fi

        echo "Updating $SOURCE_BRANCH..."
        git fetch origin "$SOURCE_BRANCH"
        git pull --ff-only origin "$SOURCE_BRANCH"

        echo "Building $ARTIFACT_ID from $SOURCE_BRANCH..."
        mvn clean package
    )

    if [ $? -ne 0 ]; then
        echo "Build from $SOURCE_BRANCH failed. Exiting."
        exit 1
    fi
}

read_source_version() {
    (
        cd "$SOURCE_WORKTREE_DIR" || exit 1
        mvn help:evaluate -Dexpression=project.version -q -DforceStdout
    )
}

# Detect OS
if [[ "$OSTYPE" == "darwin"* ]]; then
    OS="macOS"
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    OS="Linux"
else
    echo "Unsupported operating system. This script only works on macOS and Linux."
    exit 1
fi

# OS-specific package manager and installation commands
if [ "$OS" == "macOS" ]; then
    PKG_MANAGER="brew"
    INSTALL_CMD="brew install"
    if ! command_exists brew; then
        echo "Homebrew is not installed. Please install it first."
        echo "You can install Homebrew by running:"
        echo '/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"'
        exit 1
    fi
elif [ "$OS" == "Linux" ]; then
    if command_exists apt-get; then
        PKG_MANAGER="apt-get"
        INSTALL_CMD="sudo apt-get install -y"
    elif command_exists yum; then
        PKG_MANAGER="yum"
        INSTALL_CMD="sudo yum install -y"
    else
        echo "Unsupported Linux distribution. Please install the required packages manually."
        exit 1
    fi
fi

# Check for required commands
install_missing_command git git
install_missing_command mvn maven

if ! command_exists java; then
    echo "java is not installed. Please install JDK 17 or newer."
    exit 1
fi

if [ "$OS" == "macOS" ]; then
    install_missing_command shasum perl
    install_missing_command md5
elif [ "$OS" == "Linux" ]; then
    install_missing_command shasum perl
    install_missing_command md5sum coreutils
fi

prepare_source_worktree

jar_path="$SOURCE_WORKTREE_DIR/target/$JAR_NAME"

if [ ! -f "$jar_path" ]; then
    echo "Error: Could not find built jar at $jar_path"
    exit 1
fi

source_version=$(read_source_version)
if [ -z "$source_version" ]; then
    echo "Error: Could not read project.version from $SOURCE_BRANCH."
    exit 1
fi

if [ -n "$PUBLISH_VERSION" ]; then
    version="$PUBLISH_VERSION"
    if [ "$version" != "$source_version" ]; then
        echo "Using publish version $version; source version is $source_version."
    fi
else
    version="$source_version"
fi

echo "Publishing $ARTIFACT_ID $version from $jar_path..."

# Run Maven install command
mvn install:install-file \
    -DgroupId="$GROUP_ID" \
    -DartifactId="$ARTIFACT_ID" \
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
cd "$GROUP_PATH"/"$ARTIFACT_ID"/"$version" || exit

for file in "$ARTIFACT_ID"-"$version".{jar,pom}; do
    if [ "$OS" == "macOS" ]; then
        shasum -a 1 "$file" > "$file.sha1"
        md5 "$file" > "$file.md5"
    elif [ "$OS" == "Linux" ]; then
        sha1sum "$file" > "$file.sha1"
        md5sum "$file" > "$file.md5"
    fi
done

echo "Deployment complete. Files are ready to be committed and pushed to the repository."
