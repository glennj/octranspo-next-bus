-- study https://stackoverflow.com/questions/55168847/read-html-page-in-lua
--

curl = require("cURL")               -- https://luarocks.org/modules/moteus/lua-curl
json = require("dkjson")             -- https://luarocks.org/modules/dhkolf/dkjson
cli  = require("cliargs")            -- https://luarocks.org/modules/amireh/lua_cliargs
xml2lua    = require("xml2lua")      -- https://luarocks.org/modules/manoelcampos/xml2lua
xmlhandler = require("xmlhandler.tree") -- ibid.

URL = 'https://api.octranspo1.com/v1.2/GetNextTripsForStop'

-- globals
args = {}
config = {}

----------------------------------------------------------
main = function ()
    args = parse_args()
    config = get_config()
    print(os.date("At %c"))

    for _, stop in ipairs(config.buses) do
        if  (args.work and stop.from == 'work') or
            (args.home and stop.from == 'home')
        then
            for _, route in ipairs(stop.routes) do
                print_trips(stop.stop, route)
            end
        end
    end
end

parse_args = function()
    cli:option("-d", "debug mode")
    cli:option("-v", "verbose")
    cli:option("--home", "trips from Home")
    cli:option("--work", "trips from Work")

    local args, err = cli:parse(arg)
    if not args and err then
        print("error: " .. err)
        os.exit(1)
    end

    if args.d then
        args.v = true
    end
    if not args.home and not args.work then
        args.home = true
        args.work = true
    end

    return args
end

get_config = function()
    local configfile = (
        os.getenv('XDG_CONFIG_HOME') or os.getenv('HOME')
    ) .. "/.config/octranspo.json"

    local f = io.open(configfile)
    assert(f, "Cannot open " .. configfile)

    local contents = f:read('a')
    assert(contents, "Cannot read " .. configfile)
    f:close()

    local config = json.decode(contents)
    assert(contents, "Cannot parse " .. configfile)

    if args.v then
        print(contents)
    end
    return config
end

print_trips = function(stop, route)
    local data = get_route_data(stop, route)

    if args.d then
        print(json.encode(data))
        return
    end

    local r = data.Route.RouteDirection
    if not r then return end

    print(("\n%d %s"):format(route.number, data.StopLabel[1]))

    local route_info = get_route_info(route, r)
    if not route_info then
        print "  no buses"
        return
    end

    if args.v then
        print(json.encode(route_info))
    end

    for _, trip in ipairs(route_info.Trips.Trip) do
        print_trip(route.number, trip)
    end
end

print_trip = function(routeno, trip)
    local dest = trip.TripDestination:gsub(" / .*", "")
    local arrival = trip.AdjustedScheduleTime
    local bus_type = trip.BusType:sub(1,1)
    if bus_type == "4" then
        bus_type = "short"
    elseif bus_type == "6" then
        bus_type = "articulated"
    elseif bus_type == "D" then
        bus_type = "double decker"
    end
    if trip.BusType:find("B") then
        bus_type = bus_type .. " with bike rack"
    end
    print(("  %3d min -> %d %s (%s)"):format(arrival, routeno, dest, bus_type))
end

get_route_data = function(stop, route)
    local params = {}
    for key, val in pairs(config.auth) do
        params[key] = val
    end
    params.stopNo = stop
    params.routeNo = route.number
    return parse_xml(get_xml(params))
end

-- r might be a json object, or a list of objects
get_route_info = function(route, r)
    if #r == 0 then
        if  route.direction == r.Direction and
            tostring(route.number) == tostring(r.RouteNo)
        then
            return r
        end
    else
        for _, rr in ipairs(r) do
            if  route.direction == rr.Direction and
                tostring(route.number) == tostring(rr.RouteNo)
            then
                return rr
            end
        end
    end
end

get_xml = function(params)
    local form = curl.form()
    for k,v in pairs(params) do
        form:add_content(k,v)
    end
    local xml = {}
    curl.easy({
          url = URL,
          writefunction = function(a, b)
              local s = type(a) == 'string' and a or b
              xml[#xml+1] = s
              return #s
          end,
        })
        :setopt_httppost(form)
        :perform()
        :close()
    return table.concat(xml)
end

parse_xml = function(xml)
    local data = xmlhandler:new()
    local parser = xml2lua.parser(data)
    parser:parse(xml)
    return data.root['soap:Envelope']['soap:Body'].GetNextTripsForStopResponse.GetNextTripsForStopResult
end

----------------------------------------------------------
main()
