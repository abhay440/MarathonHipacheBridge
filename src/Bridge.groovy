#!/usr/bin/env groovy

@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.7' )
@Grab(group='redis.clients', module='jedis', version='2.1.0')
@Grab('log4j:log4j:1.2.17')

import redis.clients.jedis.Jedis
import groovy.json.JsonSlurper
import groovyx.net.http.*
import static groovyx.net.http.ContentType.*
import static groovyx.net.http.Method.*
import org.apache.log4j.*
import groovy.util.logging.*

// Set up command line args
def cli = new CliBuilder(usage:'Bridge.groovy [options]')
cli.r(longOpt:'RedisHost', args:2, valueSeparator:':', argName:'HOST:PORT',
        'indicate Redis host and port')
cli.P(longOpt:'AppsPath', args: 1, argName:'PATH', 'Example: /v2/apps')
cli.m(longOpt:'MarathonUrl', args:1, argName:'URL', 'Marathon url')
cli.i(longOpt:'Interval', args:1, argName:'INTERVAL', 'Interval in seconds at which the bridge syncs Redis with Marathon')

// Start execution
if (!args) {
    cli.usage()
} else {
    def options = cli.parse(args)
    new BridgeSynchronizer().runBridge(options.rs.get(0),  Integer.parseInt(options.rs.get(1)),
                                       options.P, options.m, Integer.parseInt(options.i))
}

@Log4j
class BridgeSynchronizer {

    // Get a list of all apps that are not associated with hipcheck
    def getAppList(HTTPBuilder http, String appsPath) {
        http.request(GET, JSON) {
            uri.path = appsPath

            response.success = { resp, json ->
                def appList = []
                json.apps.id.each {
                    if (!it.toString().contains('hipcheck')) {
                        appList.add(it.toString())
                    }
                }
                return appList
            }

            response.failure = { resp ->
                log.info("Unexpected error when retieving list of apps from marathon: ${resp.statusLine.statusCode} : ${resp.statusLine.reasonPhrase}")
            }
        }
    }

    // Get a list of Redis frontends and active hosts from Marathon
    def getMarathonData(HTTPBuilder http, String appPath) {
        http.request(GET, JSON) {
            uri.path = appPath

            response.success = { resp, json ->

                def redisKeys = []

                // If no front end is listed in Marathon, no changes will be made to Redis
                if (json.app.labels.hipacheFrontend) {
                    json.app.labels.hipacheFrontend.tokenize(',').each {
                        redisKeys.add('frontend:' + it)
                    }
                }

                def marathonHosts = []
                json.app.tasks.each {
                    if (it.healthCheckResults.alive) {
                        for (port in it.ports) {
                            marathonHosts.add('http://' + it.host + ':' + port)
                        }
                    }
                }

                return [redisKeys, marathonHosts]
            }

            response.failure = { resp ->
                log.info("Unexpected error when retrieving information for $appPath: ${resp.statusLine.statusCode} : ${resp.statusLine.reasonPhrase}")
            }
        }
    }

    // Sync Redis hosts with Marathon hosts for a single app
    def sync(Jedis jedis, List keys, List marathonHosts) {

        for (key in keys) {

            // If a frontend listed in Marathon does not exist in Redis, add new key
            // with the name of the app as the first item in the list
            if (!jedis.exists(key)) {
                // if key value is frontend:xxx.crsinc.com, xxx is assumed to be the app name
                def appName = key.tokenize(':').get(1).tokenize('.').get(0)
                jedis.rpush(key, appName)
                log.info("Added new key $key to Redis for app $appName")
            }

            def redisHosts = jedis.lrange(key, 1, -1)

            // If Redis does not contain a healthy host listed in Marathon, add it to Redis
            marathonHosts.each {
                if (!redisHosts.contains(it)) {
                    jedis.rpush(key, it)
                    log.info("Added new host $it for $key in Redis")
                }
            }

            // If Redis contains a host not listed in Marathon, remove it from Redis
            redisHosts.each {
                if (!marathonHosts.contains(it)) {
                    def hostsRemoved = jedis.lrem(key, 0, it)
                    log.info("$hostsRemoved instances of $it removed for $key in Redis")
                }
            }
        }
    }

    // Sync Marathon and Redis data for all apps in given list
    def syncApps(HTTPBuilder http, Jedis jedis, List appList, String appsPath) {
        for (appId in appList) {
            log.info("Syncing for ${appId}")
            def redisKeys = []
            def marathonHosts = []
            (redisKeys, marathonHosts) = getMarathonData(http, "${appsPath}$appId")
            sync(jedis, redisKeys, marathonHosts)
        }
    }

    // Periodically sync bridge for all apps
    def runBridge(String redisHost, int redisPort, String appsPath, String marathonUrl, int interval) {

        // Configure logger
        log.level = Level.INFO
        log.addAppender(new FileAppender(new TTCCLayout(), 'Bridge.log'));

        log.info("Logger Initialized")
        use(TimerMethods) {
            // Connect to Redis
            Jedis jedis = new Jedis(redisHost, redisPort)

            // Connect to Marathon to get a list of apps
            def http = new HTTPBuilder(marathonUrl)
            def appList = getAppList(http, appsPath)

            def timer = new Timer()
            def task = timer.runEvery(1000, interval * 1000) {
                println()
                syncApps(http, jedis, appList, appsPath)
                log.info("Bridge synced at ${new Date()}.")

            }
            log.info("Initializing bridge at ${new Date()}.")
        }
    }
}

class GroovyTimerTask extends TimerTask {
    Closure closure
    void run() {
        closure()
    }
}

class TimerMethods {
    static TimerTask runEvery(Timer timer, long delay, long period, Closure codeToRun) {
        TimerTask task = new GroovyTimerTask(closure: codeToRun)
        timer.schedule(task, delay, period)
    }
}
