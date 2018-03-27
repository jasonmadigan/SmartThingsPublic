/**
 *  MiHome Adapter
 *
 *  Copyright 2016 Alex Lee Yuk Cheung
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *	VERSION HISTORY - FORMER VERSION NOW RENAMED AS ADAPTER PLUS
 *	17.09.2017: 2.0a - Disable setting device to Offline on unexpected API response.
 *	23.11.2016:	2.0 - Remove extra logging.
 *
 *	10.11.2016:	2.0 BETA Release 3 - Merge Light Switch and Adapter functionality into one device type.
 *	10.11.2016:	2.0 BETA Release 2.1 - Bug fix. Stop NumberFormatException when creating body object.
 *	09.11.2016:	2.0 BETA Release 2 - Added support for MiHome multiple gangway devices.
 *
 *	08.11.2016:	2.0 BETA Release 1 - Support for MiHome (Connect) v2.0. Inital version of device.
 */
metadata {
	definition (name: "MiHome Adapter", namespace: "alyc100", author: "Alex Lee Yuk Cheung") {
		//capability "Actuator"
		//capability "Polling"
		capability "Refresh"
		capability "Switch"
        
        command "on"
        command "off"
        
        attribute "lastCheckin", "String"
	}

	tiles(scale: 2) {
		multiAttributeTile(name: "switch", type:"lighting", width:6, height:4, canChangeIcon: true){
            tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
                 attributeState "on", label:'${name}', action:"off", icon:"st.Home.home30", backgroundColor:"#00a0dc", nextState:"turningOff"
                 attributeState "off", label:'${name}', action:"on", icon:"st.Home.home30", backgroundColor:"#ffffff", nextState:"turningOn"
                 attributeState "turningOn", label:'${name}', icon:"st.Home.home30", backgroundColor:"#f1d801", nextState:"on"
                 attributeState "turningOff", label:'${name}', icon:"st.Home.home30", backgroundColor:"#f1d801", nextState:"off"
                 attributeState "offline", label:'${name}', icon:"st.switches.switch.off", backgroundColor:"#e86d13"
 			}
            tileAttribute ("device.lastCheckin", key: "SECONDARY_CONTROL") {
               	attributeState "default", label:'${currentValue}'
           	}
        }
        standardTile("refreshTile", "capability.refresh", width: 2, height: 2) {
        	state ("default", label:'Refresh', action:"refresh", icon:"st.secondary.refresh")
    	}
        standardTile("onButton", "command.switch", width: 2, height: 2) { //was capability.Switch
			state "default", label: 'On', action:"on", icon:"st.Home.home30"
        }
        standardTile("offButton", "command.switch", width: 2, height: 2) { //was capability.Switch
			state "default", label: 'Off', action:"off", icon:"st.Home.home30"
        }
        
        main("switch")
        details("switch", "onButton", "offButton", "refreshTile")
	}
    def rates = [:]
	rates << ["5" : "Refresh every 5 minutes (eTRVs)"]
	rates << ["10" : "Refresh every 10 minutes (Power Monitors)"]	
	rates << ["15" : "Refresh every 15 minutes (Sockets switched by other systems)"]
	rates << ["30" : "Refresh every 30 minutes - Default (Sockets)"]

	preferences {
		section(title: "Check-in Interval") {
        paragraph "Run a Check-in procedure every so often."
        input name: "refreshRate", type: "enum", title: "Refresh Rate", options: rates, description: "Select Refresh Rate", required: false
		}
        section(title: "Check-in Info") {
            paragraph "Display check-in info"
            input "checkinInfo", "enum", title: "Show last Check-in info", options: ["Hide", "MM/dd/yyyy h:mm", "MM-dd-yyyy h:mm", "dd/MM/yyyy h:mm", "dd-MM-yyyy h:mm"], description: "Show last check-in info.", defaultValue: "dd/MM/yyyy h:mm", required: true
        }
	}
}
// parse events into attributes
def parse(String description) {
	log.debug "Parsing ${description}"
	// TODO: handle 'switch' attribute
}

//	===== Update when installed or setting changed =====
def installed() {
	log.info "installed"
	runIn(02, initialize)
}

def updated() {
	log.info "updated"
	unschedule(refreshRate)
    unschedule(off)
    unschedule(on)
    state.counter = state.counter
    log.info "updated State counter ${state.counter}"
    runIn(02, initialize)
}

def initialize() {
	log.info "initialize"
    state.counter = 0
	switch(refreshRate) {
		case "5":
			runEvery5Minutes(refresh)
			log.info "Refresh Scheduled for every 5 minutes"
			break
		case "10":
			runEvery10Minutes(refresh)
			log.info "Refresh Scheduled for every 10 minutes"
			break
		case "15":
			runEvery15Minutes(refresh)
			log.info "Refresh Scheduled for every 15 minutes"
			break
		default:
			runEvery30Minutes(refresh)
			log.info "Refresh Scheduled for every 30 minutes"
    	log.info "State counter at ${state.counter}"
	}
}

def uninstalled() {
    unschedule()
}

def poll() {
	//log.debug "Executing poll for ${device} ${this} ${device.deviceNetworkId}"
    def body = []
    if (device.deviceNetworkId.contains("/")) {
    	body = [id: (device.deviceNetworkId.tokenize("/")[0].toInteger()), socket: (device.deviceNetworkId.tokenize("/")[1].toInteger())]
    }
    else {
    	body = [id: device.deviceNetworkId.toInteger()]
    }
    def resp = parent.apiGET("/subdevices/show?params=" + URLEncoder.encode(new groovy.json.JsonBuilder(body).toString()))
    //log.debug "poll data ${resp.status} removed ${resp.data}" 
    if (resp.status != 200) {
		log.error "Unexpected result in poll ${resp.status}" //  as not req ${resp.data}
        sendEvent(name: "switch", value: '${currentValue}', descriptionText: "The device failed POLL") // was "offline"
		return []
	}
    def power_state = resp.data.data.power_state
    if (power_state != null) {
    	sendEvent(name: "switch", value: power_state == 0 ? "off" : "on")
    }
	def lastRefreshed = state.lastRefreshed
    state.lastRefreshed = now()
    def checkinInfoFormat = (settings.checkinInfo ?: 'dd/MM/yyyy h:mm')
    def now = ''
    if (checkinInfoFormat != 'Hide') {
        try {
            now = 'Last Check-in: ' + new Date().format("${checkinInfoFormat}a", location.timeZone)
        } catch (all) { }
    	sendEvent(name: "lastCheckin", value: now, displayed: false)
    }
    log.info "POLL All good ${resp.status} ${device}"
}

def refresh() {
	log.info "Executing adapter refresh"
	poll()
}

def on() {
	//state.counter = state.counter
	log.info "Executing on"
    def body = []
    if (device.deviceNetworkId.contains("/")) {
    	body = [id: (device.deviceNetworkId.tokenize("/")[0].toInteger()), socket: (device.deviceNetworkId.tokenize("/")[1].toInteger())]
    } else {
    	body = [id: device.deviceNetworkId.toInteger()]
    }
    def resp = parent.apiGET("/subdevices/power_on?params=" + URLEncoder.encode(new groovy.json.JsonBuilder(body).toString()))
    if (resp.status != 200) {
    		log.error "Unexpected result in on poll ${resp.status} //${resp.data}"
          	if (state.counter == null || state.counter >= 7) {
				state.counter = 0
			}
        	if (state.counter == 6) {
            	sendEvent(name: "switch", value: "offline", descriptionText: "Error turning On ${state.counter} times. The device is offline")
                state.counter = 0
                return []
			}
		state.counter = state.counter + 1
        sendEvent(name: "switch", value: '${currentValue}', descriptionText: "error turning on ${state.counter} try")
        log.warn "runnting on again ${state.counter} attempt"
        runIn(013, on)
	}
   	else {
    	state.counter = 0
        log.info "ON All good ${resp.status}"
  		def power_state = resp.data.data.power_state
    		if (power_state != null) {
    			sendEvent(name: "switch", value: power_state == false ? "off" : "on")
        unschedule(on)
    		}
    }
}

def off() {
	//state.counter = state.counter
	log.info "Executing off"
    def body = []
    if (device.deviceNetworkId.contains("/")) {
    	body = [id: (device.deviceNetworkId.tokenize("/")[0].toInteger()), socket: (device.deviceNetworkId.tokenize("/")[1].toInteger())]
    }
    else {
    	body = [id: device.deviceNetworkId.toInteger()]
    }
	def resp = parent.apiGET("/subdevices/power_off?params=" + URLEncoder.encode(new groovy.json.JsonBuilder(body).toString()))
    // log.debug "off data ${resp.status} ${resp.data}"
    if (resp.status != 200) {
    	log.error "Unexpected result in off poll ${resp.status}" //${resp.data}
        if (state.counter == null || state.counter >= 7) {
			state.counter = 0
		}
        	if (state.counter == 6) {
            	sendEvent(name: "switch", value: "offline", descriptionText: "Error turning Off ${state.counter} times. The device is offline")
                state.counter = 0
                return []
			}
		state.counter = state.counter + 1
        sendEvent(name: "switch", value: '${currentValue}', descriptionText: "error turning off ${state.counter} try")
        log.warn "running off again ${state.counter} attempt"
		runIn(07, off)
	}
    else {
    	state.counter = 0
        log.info "OFF All good ${resp.status}"
        def power_state = resp.data.data.power_state
    		if (power_state != null) {
    			sendEvent(name: "switch", value: power_state == false ? "off" : "on")
        unschedule(off)
    		}
    }
}