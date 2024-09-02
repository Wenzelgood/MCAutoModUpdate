# MCAutoModUpdater

MCAutoModUpdater is a Java-based application designed to manage and update modifications (mods) on a remote server via SFTP (SSH File Transfer Protocol). It provides an easy-to-use interface and robust functionalities for checking, downloading, and updating mod files.

## Features

- **SFTP Connection**: Connect securely to remote servers using SFTP.
- **Mod Management**: Check, download, and update mods efficiently.
- **Multi-Threaded Operations**: Perform parallel updates to optimize performance.
- **Logging Support**: Track operations with configurable logging using SLF4J.

## Getting Started

### Prerequisites

- Java Development Kit (JDK) 11 or higher
- Gradle 6.0 or higher
- An SFTP server with credentials

### Installation

1. Clone the repository:

    ```bash
    git clone https://github.com/yourusername/mod-updater.git
    cd mod-updater
    ```

2. Build the project using Gradle:

    ```bash
    ./gradlew build
    ```

3. Create a shadow JAR for distribution:

    ```bash
    ./gradlew shadowJar
    ```

   The output JAR will be located in the `build/libs` directory.

### Configuration

Before running the application, configure the SFTP connection parameters in the `ModUpdater` class:

```java
// Constants for SFTP connection
private static final String SFTP_SERVER = "your_sftp_server";
private static final int SFTP_PORT = 22;
private static final String SFTP_USER = "your_username";
private static final String SFTP_PASSWORD = "your_password"; // Or use environment variables
```
### Running the Application

Run the application using the generated JAR file:

```bash
java -jar build/libs/myapp-1.0.jar
```

## Usage

1. Start the application.
2. Connect to the SFTP server.
3. Select the mod files you want to update or check.
4. Use the GUI to manage your mods and view logs.

## Built With

- [sshj](https://github.com/hierynomus/sshj) - Java SSH client for SFTP operations.
- [Apache Commons Codec](https://commons.apache.org/proper/commons-codec/) - For various encoding utilities.
- [SLF4J](http://www.slf4j.org/) - Simple Logging Facade for Java.

## Contributing

Contributions are welcome! Please open an issue or submit a pull request for any improvements or bug fixes.

### Steps to Contribute

1. Fork the repository.
2. Create a new branch (`git checkout -b feature/your-feature`).
3. Commit your changes (`git commit -am 'Add your feature'`).
4. Push to the branch (`git push origin feature/your-feature`).
5. Open a Pull Request.

## License

This project is licensed under the [MIT License](https://opensource.org/license/MIT).
