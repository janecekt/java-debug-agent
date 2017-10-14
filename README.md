# Features #

* JavaAgent which can be used to intercept invocations selected method (even JDK method)
* The agent uses JavaAssist library to modify the code when classes are being loaded.
* For the selected methods - every invocation is logged (when method returns)
    * Logging is done to standard error
    * If method returns normally
        * Method name, all input arguments and return value are logged
    * If method throws an exception (Runtime/Checked)
        * Method name all, input arguments and exception are logged
        * The exception is then rethrown (i.e. the behaviour of the method is not modified)

* You can use this to troubleshoot complex real-world applications, or just as an example to how to just JavaAssist when writing your own agents.

## Why would I need this ##

* It is often difficult or impossible get enough debug information for a specific issue especially when using third-party libraries (or JDK libraries).
* It may not be convenient (or even possible) to add extra logging into the code just to understand what's going on.
* **Example:**
   * You need to understand when your application is connecting (to which IP)
   * What DNS lookups it is making and what IP is is resolving to.
* **Real life situations:**
   * Consider your app is connecting to another service which is load-balanced using DNS round-robin and is failing intermittently.
        * In this case you need to know (at least while debugging) what's IP address you are connecting to.
   * Consider you want to troubleshoot Kerberos Authentication
        * In this case you can enable Kerberos debugging but unfortunately it does not log the IP it is connecting to.
        * You need to understand the underlying DNS lookups and outbound UDP connections
        * Since the code is in JDK you cannot change it or add your custom logging (at least not easily)

## Example Usage ##

The java code:

```java
    InetAddress.getByName("www.google.com")
    InetAddress.getByName("www.google.con")
```

Would produce the output (to standard error):
<pre>
    2017-10-14 17:38:41.379 [main] DebugAgent InetAddress::getByName(www.google.com) => www.google.com/172.217.16.100
    2017-10-14 17:38:41.430 [main] DebugAgent InetAddress::getByName(www.google.con) => java.net.UnknownHostException: www.google.con: Name or service not known
</pre>


## How to run the agent ##

With default arguments:
```bash
java -javaagent:target/debug-agent-0.1-SNAPSHOT.jar \
    -jar application.jar
```

With all arguments (enabling debug / defining methods to be intercepted explicitly):
```bash
java -javaagent:target/debug-agent-0.1-SNAPSHOT.jar=debug=true;methods=java.net.InetAddress::getByName(java.lang.String)|java.net.InetAddress::getByName(java.lang.String, java.net.InetAddress) \
    -jar application.jar
```

#### Agent arguments (format: key1=value1;key2=value2) ####
* **debug**
    * indicates whether debug logging should be enabled (disabled by default)
    * debug=true means debug should be enabled.
* **methods**
    * definition of the methods to be intercepted
    * Example: <pre>java.net.InetAddress::getByName(java.lang.String)</pre>
    * You can include multiple methods separated by **|**
    * If omitted - if omitted the a DNS lookups, opening TCP/UDP connections is logged.