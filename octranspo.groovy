// https://github.com/jgritman/httpbuilder/wiki/RESTClient 
@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7')
@Grab('oauth.signpost:signpost-core:1.2.1.2')
@Grab('oauth.signpost:signpost-commonshttp4:1.2.1.2')

import groovyx.net.*
import groovyx.net.http.*
import groovyx.net.http.RESTClient
import static groovyx.net.http.ContentType.*
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.xml.XmlUtil
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** **********************************************************************
 * OCTranspo.groovy
 *
 * Given an array of interesting bus stops, send REST queries to octranspo1.com
 * to retrieve the next few arrivals
 *
 */
// Ref: http://www.octranspo.com/developers/documentation

def ENV = System.getenv()
def config_dir = ENV.containsKey('XDG_CONFIG_HOME') ? ENV['XDG_CONFIG_HOME'] : ENV['HOME'] + '/.config'
def config_file = new File(config_dir, 'octranspo.json')

def cli = new CliBuilder( usage: "${this.class.getName()} [-options]" )
cli.H(longOpt: 'home',  'show buses from home stops')
cli.W(longOpt: 'work',  'show buses from work stops')
cli.d(longOpt: 'debug', 'show raw XML')
cli.h(longOpt: 'help',  'show help text')
cli.v(longOpt: 'verbose',  'show some verbose information')

def options = cli.parse(args)

if (options.help) {
    println 'OCTranspo bus arrivals for some stops.'
    cli.usage()
    println """
The bus routes to be queried are to be configured in ${config_file.getPath()}
That file contains a JSON object consisting of:
    * key "auth" is an object containing the appID and apiKey
    * key "buses" is an object containing a list of objects,
        * each object has keys "stop", "from" and "routes",
        * "routes" is a list of objects with keys "number" and "direction".

For example:
    {
        "auth": {"appID": 1234, "apiKey": "deadbeef"},
        "buses": {
            [
                {"stop": 7401, "from": "work", "routes": [{"number": 16, "direction": "Westbound"}]},
                {"stop": 3012, "from": "work", "routes": [
                    {"number": 11, "direction": "Westbound"},
                    {"number": 87, "direction": "Northbound"}
                ]},
                {"stop": 4960, "from": "home", "routes": [
                    { "number": 11, "direction": "Eastbound" },
                    { "number": 16, "direction": "Eastbound" },
                    { "number": 87, "direction": "Southbound" }
                ]}
            ]
        }
    }
"""
    System.exit 0
}

assert (config_file.exists() && config_file.isFile())

def config = new JsonSlurper().parse(new FileReader(config_file))
assert config.containsKey('auth')

def params = config.auth
assert params.containsKey('appID') && params.containsKey('apiKey')


def formatter = DateTimeFormatter.ofPattern('E d MMM Y, h:mm:ss a')
def today = LocalDateTime.now().format(formatter)
println "At $today:\n"

if (options.verbose || options.debug) {
    println JsonOutput.prettyPrint( JsonOutput.toJson( config.buses ))
    println ""
}

/* *** global vars *** */
url = 'https://api.octranspo1.com'
url_path = '/v1.2/GetNextTripsForStop'
http = new RESTClient( url )

config.buses
    .findAll {
        (options.home && it.from == "home") ||
        (options.work && it.from == "work") ||
        !(options.home ^ options.work)
    }
    .each { stop ->
        stop.routes.each { route ->
            params.stopNo = stop.stop
            params.routeNo = route.number
            options.debug ?
                dump_trips(params) :
                print_trips(route, params)
        }
    }

/* ********************************************************* */
def print_trips(route, params) {
    http.post( path: url_path, body: params, requestContentType: URLENC ) { resp, xml ->
        assert resp.status == 200
        def routeno, dir, stop
        def seen = false
        xml.depthFirst().each { node ->
            switch ( node.name() ) {
                case "STOPLABEL":
                    //println "Stop: $node"
                    stop = node.text()
                    break
                case "ROUTEDIRECTION":
                    routeno = node.ROUTENO.text()
                    dir = node.DIRECTION.text()
                    if (!seen) {
                        println "$routeno $stop"
                        seen = true
                    }
                    break
                case "TRIP":
                    if (dir == route.direction) {
                        def dest = node.TRIPDESTINATION.text().replaceFirst(" / .*", "")
                        def arrival = node.ADJUSTEDSCHEDULETIME.text()
                        def bustype = node.BUSTYPE.text()
                        def type
                        switch (bustype.take(1)) {
                            case "4": type = "short"; break
                            case "6": type = "articulated"; break
                            case "D": type = "double decker"; break
                        }
                        if (bustype.contains("B")) {
                            type += " with bike rack"
                        }
                        //println "$routeno $dest in $arrival minutes ($type)"
                        printf("   %3s min -> %s %s (%s)\n", arrival, routeno, dest, type)
                    }
                    break
            }
        }
        println ""
    }
}

def dump_trips(params) {
    def query = params.inject([]) {pairs, k, v -> pairs + "$k=$v"}.join("&")
    println "curl -d '${query}' ${url}${url_path}"
    println ""
    http.post(
        path: url_path,
        body: params,
        requestContentType: URLENC,
        contentType: TEXT,
        headers: [Accept: 'application/xml'],
    ) { resp, data ->
        assert resp.status == 200
        println XmlUtil.serialize(data.getText())
        println ""
    }
}
