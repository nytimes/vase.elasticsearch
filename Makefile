.DEFAULT_GOAL := help
JAR := vase-elasticsearch.jar

help: ## Display this help section
	@awk 'BEGIN {FS = ":.*?## "} /^[\/a-zA-Z0-9_-]+:.*?## / {printf "\033[36m%-45s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)


dev/infra: ## Run the long-running infra needed for development
	docker-compose down --remove-orphans && docker-compose up

dev/run: ## Run the dev demo service
	clojure -M:test:service

dev/index: ## Index data in the demo
	clojure -M:test:index

clean:
	rm -rf target *.jar

# This step will download all of the dependencies into a folder within
# the working directory. Because drone will wipe out everything not in
# the directory between steps, we do this to not re-download
# dependencies for each step.
init-ci:
ifeq "true" "${DRONE}"
	@clojure -A:test -Sdeps '{:mvn/local-repo ".deps"}' -e '(println "initialized")'
endif

.PHONY: test
test: ## Run unit tests
	bin/kaocha

coverage: test
	bin/coverage

jar:
	clojure -X:depstar jar :jar ${JAR}

deploy: jar
	clj -X:deploy

release/major: INCREMENT=major ## Release a major version: 1.2.3 -> 2.0.0
release/minor: INCREMENT=minor ## Release a minor version: 1.2.4 -> 1.3.0
release/patch: INCREMENT=patch ## Release a patch version: 1.2.4 -> 1.2.5
release/major-rc: INCREMENT=major-rc ## Release a major-rc version: 2.7.9 -> 3.0.0-rc.0, 4.0.0-rc.3 -> 4.0.0-rc.4
release/minor-rc: INCREMENT=minor-rc ## Release a minor-rc version: 2.7.9 -> 2.8.0-rc.0, 4.3.0-rc.0 -> 4.3.0-rc.1
release/major-release: INCREMENT=major-release  ## Release a major-realease version: 4.0.0-rc.4 -> 4.0.0, 3.2.9 -> 4.0.0
release/minor-release: INCREMENT=minor-rc ## Release a minor-release version: 8.1.0-rc.4 -> 8.2.0, 5.9.4 -> 5.10.0
release/%:
	clojure -A:release ${INCREMENT} --tag --pom
	git add pom.xml
	git commit -m '[skip ci] Update pom.xml'
	git push origin master --tags
