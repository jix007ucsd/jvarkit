/*
The MIT License (MIT)

Copyright (c) 2020 Pierre Lindenbaum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


*/
package com.github.lindenb.jvarkit.tools.structvar;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import com.github.lindenb.jvarkit.io.IOUtils;
import com.github.lindenb.jvarkit.lang.CharSplitter;
import com.github.lindenb.jvarkit.lang.StringUtils;
import com.github.lindenb.jvarkit.samtools.util.SimpleInterval;
import com.github.lindenb.jvarkit.util.JVarkitVersion;
import com.github.lindenb.jvarkit.util.bio.DistanceParser;
import com.github.lindenb.jvarkit.util.bio.SequenceDictionaryUtils;
import com.github.lindenb.jvarkit.util.bio.bed.BedLine;
import com.github.lindenb.jvarkit.util.bio.bed.BedLineCodec;
import com.github.lindenb.jvarkit.util.bio.fasta.ContigNameConverter;
import com.github.lindenb.jvarkit.util.iterator.EqualRangeIterator;
import com.github.lindenb.jvarkit.util.jcommander.Launcher;
import com.github.lindenb.jvarkit.util.jcommander.Program;
import com.github.lindenb.jvarkit.util.log.Logger;
import com.github.lindenb.jvarkit.util.picard.AbstractDataCodec;
import com.github.lindenb.jvarkit.util.samtools.ContigDictComparator;
import com.github.lindenb.jvarkit.variant.variantcontext.writer.WritingVariantsDelegate;

import htsjdk.samtools.Cigar;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordIterator;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.ValidationStringency;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.Locatable;
import htsjdk.samtools.util.RuntimeIOException;
import htsjdk.samtools.util.SequenceUtil;
import htsjdk.samtools.util.SortingCollection;
import htsjdk.samtools.util.StringUtil;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFConstants;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFStandardHeaderLines;
/**
BEGIN_DOC


END_DOC 
 */
@Program(
	name="cnvslidingwindow",
	description="detect CNV by sliding window.",
	keywords={"vcf","cnv","bam"},
	creationDate="20200127",
	modificationDate="20200127",
	generate_doc=false
	)
public class CnvSlidingWindow extends Launcher {
	private static final Logger LOG = Logger.build( CnvSlidingWindow.class).make();
	@Parameter(names={"-o","--output"},description=OPT_OUPUT_FILE_OR_STDOUT)
	private Path outputFile = null;
	@Parameter(names={"-R","--reference"},description=INDEXED_FASTA_REFERENCE_DESCRIPTION,required=true)
	private Path refPath = null;
	@Parameter(names={"-X","--exclude","--gaps","--gap"},description="gap file. A bed file containing region to exclude in the genome. "
			+ "E.g: http://hgdownload.cse.ucsc.edu/goldenPath/hg19/database/gap.txt.gz ")
	private Path excludeBedPath = null;
	@Parameter(names={"-xp","--exclude-pattern"},description="Exclude Chromosomes matching the following regex")
	private String excludeRegex="^GL\\d|^chrEBV$|^hs37d5$|^NC|_random$|Un_|^HLA\\-|_alt$|hap\\d$";
	@Parameter(names={"-w","--windows"},description="Windows definition: '(win-size;win-shift)+' .")
	private String windowDefs="500;100;1kb;100;2kb;100;3kb;100;5kb;100;6kb;100;7kb;100;8kb;100;9kb;100;10kb;1000;";
	@Parameter(names={"-x","--extend"},description="extend window by this factor.")
	private double extend = 0.3;
	@Parameter(names={"--treshold"},description="treshold",hidden=true)
	private double treshold = 0.05;
	@ParametersDelegate
	private WritingSortingCollection sorting = new WritingSortingCollection();
	@ParametersDelegate
	private WritingVariantsDelegate writingVariantsDelegate = new WritingVariantsDelegate();
	
	private static class Gt {
		int start;
		int end;
		int sample_idx;
		int cnv;
		
		int compare0(final Gt o ) {
			int i=Integer.compare(this.start, o.start);
			if(i!=0) return i;
			i=Integer.compare(this.end, o.end);
			if(i!=0) return i;
			return 0;
		}
		
		int compare1(final Gt o ) {
			int i=compare0(o);
			if(i!=0) return i;
			return Integer.compare(this.sample_idx,o.sample_idx);
		}

	}
	
	private static class GtCodec extends AbstractDataCodec<Gt> {
		@Override
		public Gt decode(final DataInputStream dis) throws IOException {
			Gt gt = new Gt();
			try {
				gt.start = dis.readInt();
				} 
			catch(final EOFException err) {
				return null;
				}
			gt.end = dis.readInt();
			gt.sample_idx = dis.readInt();
			gt.cnv = dis.readInt();
			return gt;
			}
		
		@Override
		public void encode(final DataOutputStream dos, final Gt o) throws IOException {
			dos.writeInt(o.start);
			dos.writeInt(o.end);
			dos.writeInt(o.sample_idx);
			dos.writeInt(o.cnv);
			}
		
		@Override
		public AbstractDataCodec<Gt> clone() {
			return new GtCodec();
		}
	}

	
private class Sample
	implements Closeable
	{
	final String name;
	final SamReader samReader;
	final SAMSequenceDictionary dict;
	
	Sample(final Path samFile) throws IOException {
		 this.samReader = SamReaderFactory.
				makeDefault().
				referenceSequence(CnvSlidingWindow.this.refPath).
				validationStringency(ValidationStringency.LENIENT).
				open(samFile);
		if(!this.samReader.hasIndex())
			{
			throw new RuntimeIOException("No index for "+ samFile);
			}
		final SAMFileHeader header = this.samReader.getFileHeader();
		this.dict = header.getSequenceDictionary();
		this.name = header.getReadGroups().stream().
				map(RG->RG.getSample()).
				filter(S->!StringUtil.isBlank(S)).
				findFirst().
				orElse(IOUtils.getFilenameWithoutCommonSuffixes(samFile.getFileName()));
		}
	
	@Override
	public void close() throws IOException {
		CloserUtil.close(this.samReader);
		}
	}



private static double median(final double array[],int len) {
	if(len==0) return Double.NaN;
	Arrays.sort(array,0,len);
	int mid_x = len/2;
	if(len%2==0) {
		return (array[mid_x-1]+array[mid_x])/2.0;
	} else {
		return array[mid_x];
	}
	
}


private static List<Locatable> split(final Locatable contig,final Locatable gap)
	{
	if(!contig.overlaps(gap)) throw new IllegalStateException();
	if(contig.getLengthOnReference()==0) return Collections.emptyList();
	if(gap.contains(contig)) return Collections.emptyList();
	final List<Locatable> intervals1 = new ArrayList<>(2);
	
	if(contig.getStart() < gap.getStart())
		{
		final Locatable left = new SimpleInterval(contig.getContig(),contig.getStart(),gap.getStart()-1);
		if(left.getLengthOnReference()>0) intervals1.add(left);
		}
	if(contig.getEnd() > gap.getEnd())
		{
		final Locatable right = new SimpleInterval(contig.getContig(),gap.getEnd()+1,contig.getEnd());
		if(right.getLengthOnReference()>0) intervals1.add(right);
		}
	
	return intervals1;
	}

private  boolean between(double v,double base) {
	return v>=base-this.treshold && v<=base+this.treshold;
}

private static final int CNV_UNDEFINED=-999999;
private int getCNVIndex(double normalizedDepth) {
	if(Double.isNaN(normalizedDepth)) return CNV_UNDEFINED;
	if(between(normalizedDepth,0)) return 0;
	if(between(normalizedDepth,1.5)) return 1;
	if(between(normalizedDepth,2.0)) return 2;
	if(between(normalizedDepth,-1.5)) return -1;
	if(between(normalizedDepth,0.0)) return -2;
	return CNV_UNDEFINED;
	}

@Override
public int doWork(final List<String> args) {
	final List<Sample> samples = new ArrayList<>();
	try
		{
		final SAMSequenceDictionary dict = SequenceDictionaryUtils.extractRequired(this.refPath);
		final DistanceParser distParser = new DistanceParser();
		final int windows_array[] = Arrays.stream(CharSplitter.SEMICOLON.split(windowDefs)).
			filter(S->!StringUtils.isBlank(S)).
			mapToInt(N->distParser.applyAsInt(N)).
			toArray();
		
		if(windows_array.length==0) {
			LOG.error("No window defined.");
			return -1;
		}
				
		if(windows_array.length%2!=0) {
			LOG.error("odd number of windows ? " + this.windowDefs);
			return -1;
		}
		
		final List<Path> inputBams = IOUtils.unrollPaths(args);
		if(inputBams.isEmpty()) {
			LOG.error("input bam file missing.");
			return -1;
			}
		
		

		final Set<String> sampleNames = new TreeSet<>();
		for(final Path samFile: inputBams) {
			final Sample sample = new Sample(samFile);
			if(sampleNames.contains(sample.name))
				{
				LOG.error("duplicate sample "+sample.name);
				sample.close();
				return -1;
				}
			sampleNames.add(sample.name);
			samples.add(sample);
			SequenceUtil.assertSequenceDictionariesEqual(dict, sample.dict);
			}
		
		final List<Locatable> contigs  = dict.getSequences().
				stream().
				filter(SR->!SR.getSequenceName().matches(this.excludeRegex)).
				map(SR->new SimpleInterval(SR.getSequenceName(),1, SR.getSequenceLength())).
				collect(Collectors.toCollection(ArrayList::new));
		
		if(this.excludeBedPath!=null) {
			final BedLineCodec bedLineCodec = new BedLineCodec();
			final ContigNameConverter ctgConverter = ContigNameConverter.fromOneDictionary(dict);
			try(BufferedReader br=IOUtils.openPathForBufferedReading(this.excludeBedPath)) {
				final List<SimpleInterval> exclude = br.lines().
					filter(L->!BedLine.isBedHeader(L)).
					map(L->bedLineCodec.decode(L)).
					filter(B->B!=null && !StringUtils.isBlank(ctgConverter.apply(B.getContig()))).
					map(B->new SimpleInterval(ctgConverter.apply(B.getContig()), B.getStart(), B.getEnd())).
					collect(Collectors.toList())
					;
				
				boolean done=false;
				while(!done) {
					done = true;
					int i=0;
					while(i< contigs.size()) {
						final Locatable contig = contigs.get(i);
						final Locatable overlapper = exclude.stream().filter(EX->EX.overlaps(contig)).findAny().orElse(null);
						if(overlapper!=null)  {
							contigs.remove(i);
							contigs.addAll(split(contig, overlapper));
							done= false;
							}
						else
							{
							i++;
							}
						}
					}
				}
			}
		contigs.sort(new ContigDictComparator(dict).createLocatableComparator());

		
		final Allele ref_allele = Allele.create("N", true);
		final Allele dup_allele = Allele.create("<DUP>", false);
		final Allele del_allele = Allele.create("<DEL>", false);
		final Function<Integer, List<Allele>> cnv2allele = CNV-> {
			switch(CNV) {
			case 0: return Arrays.asList(ref_allele,ref_allele);
			case 1: return Arrays.asList(ref_allele,dup_allele);
			case 2: return Arrays.asList(dup_allele,dup_allele);
			case -1: return Arrays.asList(ref_allele,del_allele);
			case -2: return Arrays.asList(del_allele,del_allele);
			default: throw new IllegalArgumentException("cnv:"+CNV);
			}
		};
		
		final Set<VCFHeaderLine> metaData = new HashSet<>();
		VCFStandardHeaderLines.addStandardFormatLines(metaData, true, VCFConstants.GENOTYPE_KEY);
		VCFStandardHeaderLines.addStandardInfoLines(metaData, true, VCFConstants.END_KEY);
		VCFHeader header = new VCFHeader(metaData,sampleNames);
		header.setSequenceDictionary(dict);
		JVarkitVersion.getInstance().addMetaData(this, header);
		
		VariantContextWriter vcw = this.writingVariantsDelegate.dictionary(dict).open(this.outputFile);
		vcw.writeHeader(header);

		
		for(final Locatable contig : contigs) {
			
			System.gc();
			final short array[] = new short[contig.getLengthOnReference()];
			SortingCollection<Gt> sorter = SortingCollection.newInstance(
					Gt.class,
					new GtCodec(),
					(A,B)->A.compare1(B),
					sorting.getMaxRecordsInRam(),
					sorting.getTmpPaths()
					);
			
		
			for(int bam_index=0;bam_index < samples.size();bam_index++) {
				final Sample sampleBam = samples.get(bam_index);
				Arrays.fill(array, (short)0);
				
				try(SAMRecordIterator iter = sampleBam.samReader.queryOverlapping(contig.getContig(),contig.getStart(),contig.getEnd())) {
					while(iter.hasNext())
						{
						final SAMRecord rec = iter.next();
						if(rec.getReadUnmappedFlag()) continue;
						if(rec.getDuplicateReadFlag()) continue;
						if(rec.isSecondaryOrSupplementary()) continue;
						if(rec.getReadFailsVendorQualityCheckFlag()) continue;
						final Cigar cigar = rec.getCigar();
						if(cigar==null || cigar.isEmpty()) continue;
						
						int refPos=rec.getStart();
						
						for(final CigarElement ce:cigar)
							{
							final CigarOperator op=ce.getOperator();
							if(op.consumesReferenceBases())
								{
								if(op.consumesReadBases())
									{
									for(int i=0;i< ce.getLength();i++)
										{
										final int idx = refPos-contig.getStart()+i;
										if(idx<0) continue;
										if(idx>=array.length) break;
										if(array[idx]==Short.MAX_VALUE) continue;
										array[idx]++;
										}
									}
								refPos+=ce.getLength();
								}
							}
						}
					}
				
				for(int widx=0;widx< windows_array.length;widx+=2) {
					final int window_size = windows_array[widx+0];
					final int extend = (int)Math.ceil(window_size * this.extend);
					if(extend<=0) continue;
					if(window_size> contig.getLengthOnReference()) continue;
					
					
					final int window_shift = windows_array[widx+1];
					
					LOG.info(contig+" "+window_size+"+-"+extend+";"+window_shift+" "+sampleBam.name);

					
					final double bound_array[] = new double[extend+extend];
					final double coverage[] = new double[window_size];
					
					for(int pos1 = contig.getStart();
							pos1 + window_size + extend + extend <  contig.getEnd();
							pos1 += window_shift) {
						
						int n=0;
						for(int x=0;x<extend;x++) {
							final int idx = pos1 - contig.getStart() + x;
							if(idx<0 || idx >= array.length) throw new IndexOutOfBoundsException(""+idx+" "+array.length+" "+contig.getLengthOnReference()+" "+x);
							if(n<0 || n>=bound_array.length) throw new IndexOutOfBoundsException(""+n+" "+bound_array.length+" "+contig.getLengthOnReference()+" "+x);
							bound_array[n++] = array[idx];
						}
						
						
						for(int x=0;x<extend;x++) {
							final int idx = pos1 -  contig.getStart() + extend + window_size + x;
							bound_array[n++]= array[idx];
						}
						
						
						int n2=0;
						for(int x=0;x<window_size;x++) {
							final int idx = pos1 -  contig.getStart() + extend + x;
							coverage[n2]= array[idx];
							n2++;
						}
											
						final double median = median(bound_array,n);
						if(median>0) {
							for(int x=0;x< n2;x++) {
								coverage[x] = coverage[x]/median;
							}
						}
						
						final double norm_depth =  median(coverage,n2);
						final int cnv = getCNVIndex(norm_depth);
						if(cnv!=CNV_UNDEFINED ) {
							final Gt gt = new Gt();
							gt.start = pos1+extend;
							gt.end = gt.start + window_size;
							gt.sample_idx = bam_index;
							gt.cnv = cnv;
							sorter.add(gt);
							}
					}
				}
			}
			sorter.setDestructiveIteration(true);
			
			try(CloseableIterator<Gt> iter=sorter.iterator()) {
				final EqualRangeIterator<Gt> eq=new EqualRangeIterator<>(iter, (A,B)->A.compare0(B));
				while(eq.hasNext()) {
					final List<Gt> row = eq.next();
					if(row.isEmpty()) continue;
					final Gt first = row.get(0);
					final Set<Allele> alleles = new HashSet<>();
					row.stream().
						flatMap(GT->cnv2allele.apply(GT.cnv).stream()).
						forEach(CNV->alleles.add(CNV));
					
					alleles.add(ref_allele);
					
					if(alleles.size()<2) continue;
					
					final VariantContextBuilder vcb = new VariantContextBuilder(null, contig.getContig(),first.start,first.end, alleles);
					vcb.attribute(VCFConstants.END_KEY, first.end);
					final List<Genotype> genotypes = new ArrayList<>(samples.size());
					for(final Gt gt:row) {
						final GenotypeBuilder gb=new GenotypeBuilder(samples.get(gt.sample_idx).name,cnv2allele.apply(gt.cnv));
						genotypes.add(gb.make());
					}
					vcb.genotypes(genotypes);
					vcw.add(vcb.make());
				}
				eq.close();
			}
			
			sorter.cleanup();
			}
		
		vcw.close();
		return 0;
		}
	catch(final Throwable err)
		{
		LOG.error(err);
		return -1;
		}
	finally
		{
		samples.forEach(S->CloserUtil.close(S));
		}
	}

public static void main(final String[] args) {
	new CnvSlidingWindow().instanceMainWithExit(args);
	}

}
