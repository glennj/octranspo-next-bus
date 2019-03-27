@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7')
@Grab('oauth.signpost:signpost-core:1.2.1.2')
@Grab('oauth.signpost:signpost-commonshttp4:1.2.1.2')

import groovyx.net.*
import groovyx.net.http.*
import groovyx.net.http.RESTClient
import static groovyx.net.http.ContentType.*
import groovy.json.JsonOutput
import groovy.inspect.*

/** ********************************************************************** 
 * OCTranspo.groovy
 *
 * Given an array of interesting bus stops, send REST queries to octranspo1.com
 * to retrieve the next few arrivals
 */

class OCTranspo {

    static app_id = '****'
    static api_key = '****'
    static url = 'https://api.octranspo1.com'
    static url_path = '/v1.2/GetNextTripsForStop'
    static interesting_stops = [
        [stop: 7401, routes: [
            [number: 2, direction: 'Westbound'],
            [number: 16, direction: 'Westbound'],
        ]],
        [stop: 3012, routes: [
            [number: 87, direction: 'Northbound']
        ]],
    ]

    def debug = false

    /**
     * The entry point
     */
    static void main(String... args) {
        def obj = new OCTranspo2()
        def options = obj.parseArgs(args)
        obj.setDebug options.debug

        if (options.verbose || options.debug) {
            println JsonOutput.prettyPrint( JsonOutput.toJson( this.interesting_stops ))
            println ""
        }

        obj.findNextBuses()
    }

    def setDebug(value) { this.debug = value }

    /** 
     * Parse the command line arguments
     *
     * @param args the array of arguments
     * @return an OptionAccessor object
     */
    def parseArgs(String... args) {
        def cli = new CliBuilder( usage: 'groovy ' + this.class.getName() + ' [-options]')
        cli.d(longOpt: 'debug', 'show raw XML')
        cli.v(longOpt: 'verbose',  'show some verbose information')
        cli.h(longOpt: 'help',  'show help text')
        def options = cli.parse(args)
        if (options.help) {
            println 'OCTranspo bus arrivals for some stops'
            cli.usage()
            System.exit 0
        }

        return options
    }

    /**
     * Loop over the list of interesting bus stops
     * and extract the next arrivals for the declared routes
     *
     * @return null
     */
    def findNextBuses() {
        def http = new RESTClient( this.url )

        this.interesting_stops.each { stop ->
            stop.routes.each { route ->
                def params = [
                    appID: this.app_id,
                    apiKey: this.api_key,
                    stopNo: stop.stop,
                    routeNo: route.number,
                ]

                this.debug 
                    ? dump_xml(http, params)
                    : get_stops(http, params, route.direction) 
            }
        }
    }

    /**
     * Extract the next stop times from the returned XML
     *
     * @params http a RESTClient object
     * @params params the necessary parameters
     * @params direction the expected direction of the bus route
     * @return null
     */
    def get_stops(http, params, direction) {
        http.post( path: this.url_path, body: params, requestContentType: URLENC ) { resp, xml ->
            assert resp.status == 200
            def routeno, dir
            xml.depthFirst().each { node ->
                switch ( node.name() ) {
                    case "STOPLABEL":
                        println "Stop: $node"
                        break
                    case "ROUTEDIRECTION":
                        routeno = node.ROUTENO
                        dir = node.DIRECTION
                        break
                    case "TRIP":
                        if (dir == direction) {
                            println "$routeno ${node.TRIPDESTINATION} in ${node.ADJUSTEDSCHEDULETIME} minutes"
                        }
                        break
                }
            }
            println ""
        }
    }

    /**
     * Dump raw XML output
     *
     * @params http a RESTClient object
     * @params params the necessary parameters
     */
    def dump_xml(http, params) {
        def query = params.inject([]) {pairs, k, v -> pairs + "$k=$v"}.join("&") 
        println "curl -d '${query}' ${this.url}${this.url_path}"
        println ""
        http.post( 
            path: this.url_path, 
            body: params, 
            requestContentType: URLENC,
            contentType: TEXT,
            headers: [Accept: 'application/xml'],
        ) { resp, data ->
            assert resp.status == 200
            println data.getText()
            println ""
        }
    }
}

