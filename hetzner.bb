#!/usr/bin/env bb

(ns hetzner
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [babashka.http-client :as http]
            [shell-smith.core :as smith]))

(def usage "Hetzner DNS Tool

Usage:
  hetzner pull [options]
  hetzner validate [options]
  hetzner push [options]
  hetzner -h | --help
  hetzner --version

Options:
  -h --help                 Show this help
  -v --version              Show version
  -t --token=<token>        Hetzner DNS API token
  -z --zones=<zones>        Zone names to operate on comma separated (if not specified, operates on all zones)
  -e --endpoint=<endpoint>  API endpoint [default: https://dns.hetzner.com/api]
")

(def version "0.1.0")

(defn make-auth-header [token]
  {"Auth-API-Token" token})

(def post-header
  {"Content-Type" "text/plain"})

(defn- get-zones [endpoint auth-header zone-names]
  (let [url (str endpoint "/v1/zones")
        response (http/get url
                           {:headers auth-header})
        zones (-> response
                  :body
                  (json/parse-string true)
                  :zones)]
    (if zone-names
      (let [zone-set (set (str/split zone-name #"\s*,\s*"))]
        (filter #(zone-set (:name %)) zones))
      zones)))

(defn- export-zone [endpoint auth-header zone]
  (let [zone-id (:id zone)
        zone-name (:name zone)
        url (str endpoint "/v1/zones/" zone-id "/export")
        response (http/get url {:headers auth-header})]
    (if (#{200} (:status response))
      (let [zone-content (:body response)]
        (spit (str zone-name ".zone") zone-content)
        (println "Exported zone" zone-name "to" (str zone-name ".zone")))
      (println "Failed to export zone" zone-name ":" (:stderr response)))))

(defn- validate-zone-file [endpoint auth-header file-path]
  (let [url (str endpoint "/v1/zones/file/validate")
        response (http/post url
                            {:headers (merge auth-header post-header)
                             :body (fs/file file-path)})]
    (#{200} (:status response))))

(defn validate-command [{:keys [endpoint token] zone-names :zones :as opts}]
  (if-not token
    (println "Error: Auth token is required")
    (let [auth-header (make-auth-header token)]
      (let [zones (get-zones endpoint auth-header zone-names)]
        (if (empty? zones)
          (println (if zone-name
                     (str "No zones found with names: " zone-names)
                     "No zones found"))
          (doseq [zone zones]
            (let [file-path (str (:name zone) ".zone")]
              (if (fs/exists? file-path)
                (if (validate-zone-file endpoint auth-header file-path)
                  (println "Zone file" file-path "is valid")
                  (println "Zone file" file-path "is invalid"))
                (println "Zone file" file-path "does not exist")))))))))

(defn- import-zone [endpoint auth-header zone file-path]
  (let [zone-id (:id zone)
        zone-name (:name zone)
        url (str endpoint "/v1/zones/" zone-id "/import")
        response (http/post url
                            {:headers (merge auth-header post-header)
                             :body (fs/file file-path)})]
    (if (#{200} (:status response))
      (println "Successfully imported zone" zone-name "from" file-path)
      (println "Failed to import zone" zone-name ":" (:stderr response)))))

(defn pull-command [{:keys [endpoint token] zone-names :zones :as opts}]
  (if-not token
    (println "Error: Auth token is required")
    (let [auth-header (make-auth-header token)]
      (let [zones (get-zones endpoint auth-header zone-names)]
        (if (empty? zones)
          (println (if zone-name
                     (str "No zones found with names: " zone-names)
                     "No zones found"))
          (doseq [zone zones]
            (export-zone endpoint auth-header zone)))))))

(defn push-command [{:keys [endpoint token] zone-names :zones :as opts}]
  (if-not token
    (println "Error: Auth token is required")
    (let [auth-header (make-auth-header token)]
      (let [zones (get-zones endpoint auth-header zone-names)]
        (if (empty? zones)
          (println (if zone-name
                     (str "No zones found with names: " zone-names)
                     "No zones found"))
          (doseq [zone zones]
            (let [file-path (str (:name zone) ".zone")]
              (if (fs/exists? file-path)
                (if (validate-zone-file endpoint auth-header file-path)
                  (do
                    (println "Zone file" file-path "is valid")
                    (import-zone endpoint auth-header zone file-path))
                  (println "Zone file" file-path "is invalid"))
                (println "Zone file" file-path "does not exist")))))))))

(defn -main [& args]
  (let [config (smith/config usage)]
    (cond
      (:help config) (println usage)
      (:version config) (println "Hetzner Tool " version)
      (:pull config) (pull-command config)
      (:push config) (push-command config)
      (:validate config) (validate-command config)
      :else (println usage))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
