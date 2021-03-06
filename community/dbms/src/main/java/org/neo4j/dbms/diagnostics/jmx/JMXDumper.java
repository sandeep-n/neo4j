/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.dbms.diagnostics.jmx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Optional;

import org.neo4j.io.fs.FileSystemAbstraction;

/**
 * Facilitates JMX Dump for current running Neo4j instance.
 */
public class JMXDumper
{
    private final Path homeDir;
    private final FileSystemAbstraction fs;
    private final PrintStream err;
    private final boolean verbose;
    private PrintStream out;

    public JMXDumper( Path homeDir, FileSystemAbstraction fs, PrintStream out, PrintStream err, boolean verbose )
    {
        this.homeDir = homeDir;
        this.fs = fs;
        this.err = err;
        this.verbose = verbose;
        this.out = out;
    }

    public Optional<JmxDump> getJMXDump()
    {
        out.println( "Finding running instance of neo4j" );

        Optional<Long> pid = getPid();
        if ( pid.isPresent() )
        {
            try
            {
                LocalVirtualMachine vm = LocalVirtualMachine.from( pid.get() );
                out.println( "Attached to running process with process id " + pid.get() );
                try
                {
                    JmxDump jmxDump = JmxDump.connectTo( vm.getJmxAddress() );
                    jmxDump.attachSystemProperties( vm.getSystemProperties() );
                    out.println( "Connected to JMX endpoint" );
                    return Optional.of( jmxDump );
                }
                catch ( IOException e )
                {
                    printError( "Unable to communicate with JMX endpoint. Reason: " + e.getMessage(), e );
                }
            }
            catch ( IOException e )
            {
                printError( "Unable to connect to process. Reason: " + e.getMessage(), e );
            }
        }
        else
        {
            out.println( "No running instance of neo4j was found. Online reports will be omitted." );
        }

        return Optional.empty();
    }

    private void printError( String message, Throwable e )
    {
        err.println( message );
        if ( verbose && e != null )
        {
            e.printStackTrace( err );
        }
    }

    private void printError( String message )
    {
        printError( message, null );
    }

    private Optional<Long> getPid()
    {
        Path pidFile = this.homeDir.resolve( "run/neo4j.pid" );
        if ( this.fs.fileExists( pidFile.toFile() ) )
        {
            try ( BufferedReader reader = new BufferedReader( this.fs.openAsReader( pidFile.toFile(), Charset
                    .defaultCharset() ) ) )
            {
                String pidFileContent = reader.readLine();
                try
                {
                    return Optional.of( Long.parseLong( pidFileContent ) );
                }

                catch ( NumberFormatException e )
                {
                    printError( pidFile.toString() + " does not contain a valid id. Found: " + pidFileContent );
                }
            }
            catch ( IOException e )
            {
                printError( "Error reading the .pid file. Reason: " + e.getMessage(), e );
            }
        }
        return Optional.empty();
    }
}
