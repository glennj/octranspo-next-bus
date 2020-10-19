import json, os, httpclient, parseopt
from strutils import isEmptyOrWhitespace

# nim c -d:ssl octranspo

const URL = "https://api.octranspo1.com/v1.3/GetNextTripsForStop"

proc usage(status: int) =
  echo """usage: octranspo [options]
where:
        -H|--home     show routes to work starting at home
        -W|--work     show routes home starting at work
        -h|--help     show help
        -v|--version  show version info"""
  quit status

proc version() =
  echo "octranspo, nim edition, v0.1"
  quit QuitSuccess

proc getConfig: JsonNode =
  var confDir = os.getEnv("XDG_CONFIG_HOME")
  if confDir == "":
    confDir = os.getEnv("HOME") / ".config"
  let confFile = confDir / "octranspo.json"
  doAssert(fileExists confFile)
  var json = readFile confFile
  json.parseJson

proc printTrips(busData: string, destination: string) =
  let data = busData.parseJson()["GetNextTripsForStopResult"]
  let stopLabel = data["StopLabel"].getStr

  proc handleRouteDirection(route: JsonNode) =
    var seen = true
    if route["Trips"].hasKey("Trip"):
      for trip in route["Trips"]["Trip"]:
        if trip["TripDestination"].getStr == destination:
          if seen:
            echo route["RouteNo"].getStr & " " & stopLabel
            seen = false
          echo "   " & trip["AdjustedScheduleTime"].getStr & " mins"

  case data["Route"]["RouteDirection"].kind
    of JArray:
      for direction in data["Route"]["RouteDirection"]:
        handleRouteDirection direction
    of JObject:
      handleRouteDirection data["Route"]["RouteDirection"]
    else:
      raise newException(ValueError, "")

proc nextTrips(stop: JsonNode, client: HttpClient, auth: JsonNode) =
  var data = newMultipartData()
  data["appID"] = auth["appID"].getStr
  data["apiKey"] = auth["apiKey"].getStr
  data["format"] = "json"
  data["stopNo"] = stop["stop"].getInt.`$`

  for route in stop["routes"]:
    data["routeNo"] = route["number"].getInt.`$`
    var content = client.postContent(URL, multipart=data)
    printTrips(content, route["destination"].getStr)

proc parseOpts(): string =
  for kind, key, val in getopt():
    case kind
      of cmdLongOption, cmdShortOption:
        case key
          of "help", "h": usage(QuitSuccess)
          of "version", "v": version()
          of "home", "H": result = "home"
          of "work", "W": result = "work"
          else: usage(QuitFailure)
      else: discard
  if result.isEmptyOrWhitespace: usage(QuitFailure)

if isMainModule:
  let conf = getConfig()
  let start = parseOpts()
  var client = newHttpClient()

  for busStop in conf["buses"]:
    if busStop["from"].getStr == start:
      nextTrips busStop, client, conf["auth"]
