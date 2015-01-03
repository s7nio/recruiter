package de.th.wildau.recruiter.web;

import java.io.Serializable;
import java.util.Locale;

import javax.enterprise.inject.Produces;
import javax.faces.bean.ManagedBean;
import javax.faces.bean.SessionScoped;
import javax.faces.context.FacesContext;

/**
 * Handle the user locale.
 * 
 * @author s7n
 *
 */
@ManagedBean
@SessionScoped
public class LocaleHome implements Serializable {

	private static final long serialVersionUID = -6547913945910172375L;

	@Produces
	private Locale locale = FacesContext.getCurrentInstance().getViewRoot()
			.getLocale();

	public String getLanguage() {
		return this.locale.getLanguage();
	}

	public Locale getLocale() {
		return this.locale;
	}

	public void setLanguage(final String language) {
		this.locale = new Locale(language);
		FacesContext.getCurrentInstance().getViewRoot().setLocale(this.locale);
	}
}