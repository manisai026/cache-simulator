JAVAC = javac
JAVA  = java
MAIN  = sim_cache
SRC   = $(MAIN).java

all: $(MAIN)

$(MAIN): $(SRC)
	$(JAVAC) $(SRC)

run: all
	$(JAVA) $(MAIN)

clean:
ifeq ($(OS),Windows_NT)
	-del /Q *.class 2>nul
else
	-rm -f *.class
endif
